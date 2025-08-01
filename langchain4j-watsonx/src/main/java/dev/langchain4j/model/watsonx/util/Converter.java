package dev.langchain4j.model.watsonx.util;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import com.ibm.watsonx.ai.chat.model.AssistantMessage;
import com.ibm.watsonx.ai.chat.model.ChatParameters;
import com.ibm.watsonx.ai.chat.model.ControlMessage;
import com.ibm.watsonx.ai.chat.model.Image.Detail;
import com.ibm.watsonx.ai.chat.model.ImageContent;
import com.ibm.watsonx.ai.chat.model.TextContent;
import com.ibm.watsonx.ai.chat.model.Tool;
import com.ibm.watsonx.ai.chat.model.ToolCall;
import com.ibm.watsonx.ai.chat.model.ToolMessage;
import com.ibm.watsonx.ai.chat.model.UserContent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.watsonx.WatsonxChatRequestParameters;
import java.util.List;

public class Converter {

    public static com.ibm.watsonx.ai.chat.model.ChatMessage toChatMessage(ChatMessage chatMessage) {
        return switch (chatMessage.type()) {
            case SYSTEM -> toSystemMessage(SystemMessage.class.cast(chatMessage));
            case AI -> toAssistantMessage(AiMessage.class.cast(chatMessage));
            case USER -> toUserMessage(UserMessage.class.cast(chatMessage));
            case CUSTOM -> toControlMessage(CustomMessage.class.cast(chatMessage));
            case TOOL_EXECUTION_RESULT -> toToolMessage(ToolExecutionResultMessage.class.cast(chatMessage));
        };
    }

    public static Tool toTool(ToolSpecification toolSpecification) {
        var parameters = nonNull(toolSpecification.parameters())
                ? JsonSchemaElementUtils.toMap(toolSpecification.parameters())
                : null;
        return Tool.of(toolSpecification.name(), toolSpecification.description(), parameters);
    }

    public static ToolExecutionRequest toToolExecutionRequest(ToolCall toolCall) {
        return ToolExecutionRequest.builder()
                .arguments(toolCall.function().arguments())
                .id(toolCall.id())
                .name(toolCall.function().name())
                .build();
    }

    public static FinishReason toFinishReason(String finishReason) {
        if (finishReason == null) return FinishReason.OTHER;

        return switch (finishReason) {
            case "length" -> FinishReason.LENGTH;
            case "stop" -> FinishReason.STOP;
            case "tool_calls" -> FinishReason.TOOL_EXECUTION;
            case "time_limit", "cancelled", "error" -> FinishReason.OTHER;
            default -> throw new IllegalArgumentException("%s not supported".formatted(finishReason));
        };
    }

    public static AiMessage toAiMessage(AssistantMessage assistantMessage) {

        List<ToolExecutionRequest> toolExecutionRequests = null;

        if (nonNull(assistantMessage.toolCalls())) {
            toolExecutionRequests = assistantMessage.toolCalls().stream()
                    .map(Converter::toToolExecutionRequest)
                    .toList();
        }

        return AiMessage.from(assistantMessage.content(), toolExecutionRequests);
    }

    public static ChatParameters toChatParameters(ChatRequest chatRequest) {
        ChatRequestParameters parameters = chatRequest.parameters();
        ChatParameters.Builder builder = ChatParameters.builder()
                .modelId(getOrDefault(parameters.modelName(), chatRequest.modelName()))
                .frequencyPenalty(getOrDefault(parameters.frequencyPenalty(), chatRequest.frequencyPenalty()))
                .maxCompletionTokens(getOrDefault(parameters.maxOutputTokens(), chatRequest.maxOutputTokens()))
                .presencePenalty(getOrDefault(parameters.presencePenalty(), chatRequest.presencePenalty()))
                .stop(getOrDefault(parameters.stopSequences(), chatRequest.stopSequences()))
                .temperature(getOrDefault(parameters.temperature(), chatRequest.temperature()))
                .topP(getOrDefault(parameters.topP(), chatRequest.topP()));

        if (nonNull(parameters.responseFormat()) || nonNull(chatRequest.responseFormat())) {
            var responseFormat = getOrDefault(parameters.responseFormat(), chatRequest.responseFormat());
            switch (responseFormat.type()) {
                case JSON -> {
                    if (nonNull(responseFormat.jsonSchema())) {
                        var name = responseFormat.jsonSchema().name();
                        var jsonSchema = JsonSchemaElementUtils.toMap(
                                responseFormat.jsonSchema().rootElement());
                        builder.withJsonSchemaResponse(name, jsonSchema, true);
                    } else {
                        builder.withJsonResponse();
                    }
                }
                case TEXT -> {
                    // Do nothing.
                }
            }
        }

        if (parameters instanceof WatsonxChatRequestParameters watsonxParameters) {
            builder.projectId(watsonxParameters.projectId());
            builder.spaceId(watsonxParameters.spaceId());
            builder.logitBias(watsonxParameters.logitBias());
            builder.logprobs(watsonxParameters.logprobs());
            builder.n(watsonxParameters.n());
            builder.seed(watsonxParameters.seed());
            builder.timeLimit(nonNull(watsonxParameters.timeLimit()) ? watsonxParameters.timeLimit() : null);
            builder.topLogprobs(watsonxParameters.topLogprobs());

            List<ToolSpecification> toolSpecifications = parameters.toolSpecifications();

            if ((isNull(parameters.toolChoice()) || parameters.toolChoice().equals(ToolChoice.REQUIRED))
                    && nonNull(watsonxParameters.toolChoiceName())) {

                if (toolSpecifications.isEmpty())
                    throw new IllegalArgumentException(
                            "If tool-choice-name is set, at least one tool must be specified.");

                builder.toolChoiceOption(null);
                builder.toolChoice(toolSpecifications.stream()
                        .filter(toolSpecification ->
                                toolSpecification.name().equalsIgnoreCase(watsonxParameters.toolChoiceName()))
                        .findFirst()
                        .map(ToolSpecification::name)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "The tool with name '%s' is not available in the list of tools sent to the model."
                                        .formatted(watsonxParameters.toolChoiceName()))));

            } else if (nonNull(parameters.toolChoice())) {
                switch (parameters.toolChoice()) {
                    case AUTO -> builder.toolChoiceOption(com.ibm.watsonx.ai.chat.model.ChatParameters.ToolChoice.AUTO);
                    case REQUIRED -> {
                        if (toolSpecifications.isEmpty())
                            throw new IllegalArgumentException(
                                    "If tool-choice is 'REQUIRED', at least one tool must be specified.");

                        builder.toolChoiceOption(com.ibm.watsonx.ai.chat.model.ChatParameters.ToolChoice.REQUIRED);
                    }
                }
            }
        }

        return builder.build();
    }

    private static ToolCall toToolCall(ToolExecutionRequest toolExecutionRequest) {
        return ToolCall.of(toolExecutionRequest.id(), toolExecutionRequest.name(), toolExecutionRequest.arguments());
    }

    private static com.ibm.watsonx.ai.chat.model.SystemMessage toSystemMessage(SystemMessage systemMessage) {
        return com.ibm.watsonx.ai.chat.model.SystemMessage.of(systemMessage.text());
    }

    private static AssistantMessage toAssistantMessage(AiMessage aiMessage) {
        if (aiMessage.hasToolExecutionRequests()) {
            var toolCalls = aiMessage.toolExecutionRequests().stream()
                    .map(Converter::toToolCall)
                    .toList();
            return AssistantMessage.tools(toolCalls);
        }
        return AssistantMessage.text(aiMessage.text());
    }

    private static com.ibm.watsonx.ai.chat.model.UserMessage toUserMessage(UserMessage userMessage) {
        return com.ibm.watsonx.ai.chat.model.UserMessage.of(
                userMessage.name(),
                userMessage.contents().stream().map(Converter::toUserContent).toList());
    }

    private static UserContent toUserContent(Content content) {
        return switch (content.type()) {
            case AUDIO, VIDEO, PDF -> throw new RuntimeException("Not implemented");
            case IMAGE -> {
                var imageContent = (dev.langchain4j.data.message.ImageContent) content;
                var mimeType = imageContent.image().mimeType();
                var base64Data = requireNonNull(imageContent.image().base64Data(), "The base64Data can not be null");
                Detail detailLevel =
                        switch (imageContent.detailLevel()) {
                            case AUTO -> Detail.AUTO;
                            case HIGH -> Detail.HIGH;
                            case LOW -> Detail.LOW;
                        };
                yield ImageContent.of(mimeType, base64Data, detailLevel);
            }
            case TEXT -> {
                var textContent = (dev.langchain4j.data.message.TextContent) content;
                yield TextContent.of(textContent.text());
            }
        };
    }

    private static ControlMessage toControlMessage(CustomMessage customMessage) {
        var attributes = customMessage.attributes();
        if (!attributes.containsKey("content"))
            throw new IllegalArgumentException(
                    "Watsonx only allows the use of CustomMessage with \"content\" attribute.");

        try {
            return ControlMessage.of((String) customMessage.attributes().get("content"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Only the String type is allowed.");
        }
    }

    private static ToolMessage toToolMessage(ToolExecutionResultMessage toolExecutionResultMessage) {
        return ToolMessage.of(toolExecutionResultMessage.text(), toolExecutionResultMessage.id());
    }
}
