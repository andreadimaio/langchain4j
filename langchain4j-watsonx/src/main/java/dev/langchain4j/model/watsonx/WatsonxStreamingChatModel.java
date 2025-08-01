package dev.langchain4j.model.watsonx;

import static dev.langchain4j.model.ModelProvider.WATSONX;
import static java.util.Objects.nonNull;

import com.ibm.watsonx.ai.chat.ChatHandler;
import com.ibm.watsonx.ai.chat.ChatResponse.ResultChoice;
import com.ibm.watsonx.ai.chat.ChatService;
import com.ibm.watsonx.ai.chat.model.ChatMessage;
import com.ibm.watsonx.ai.chat.model.ChatParameters;
import com.ibm.watsonx.ai.chat.model.PartialChatResponse;
import com.ibm.watsonx.ai.chat.model.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.watsonx.util.Converter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link StreamingChatModel} implementation that integrates IBM watsonx.ai with LangChain4j.
 * <p>
 * <b>Example usage:</b>
 *
 * <pre>{@code
 * ChatService chatService = ChatService.builder()
 *     .url("https://...") // or use CloudRegion
 *     .authenticationProvider(authProvider)
 *     .projectId("my-project-id")
 *     .modelId("ibm/granite-3-8b-instruct")
 *     .build();
 *
 * WatsonxChatRequestParameters defaultRequestParameters =
 *     WatsonxChatRequestParameters.builder()
 *         .maxOutputTokens(0)
 *         .temperature(0.7)
 *         .build();
 *
 * StreamingChatModel chatModel = WatsonxStreamingChatModel.builder()
 *     .service(chatService)
 *     .defaultRequestParameters(defaultRequestParameters)
 *     .build();
 * }</pre>
 *
 *
 * @see ChatService
 * @see WatsonxChatRequestParameters
 */
public class WatsonxStreamingChatModel extends WatsonxChat implements StreamingChatModel {

    protected WatsonxStreamingChatModel(Builder builder) {
        super(builder);
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {

        List<ToolSpecification> toolSpecifications = chatRequest.parameters().toolSpecifications();

        List<ChatMessage> messages =
                chatRequest.messages().stream().map(Converter::toChatMessage).toList();

        List<Tool> tools = nonNull(toolSpecifications) && toolSpecifications.size() > 0
                ? toolSpecifications.stream().map(Converter::toTool).toList()
                : null;

        ChatParameters parameters = Converter.toChatParameters(chatRequest);
        chatProvider.chatStreaming(messages, tools, parameters, new ChatHandler() {
            @Override
            public void onCompleteResponse(com.ibm.watsonx.ai.chat.ChatResponse completeResponse) {

                ResultChoice choice = completeResponse.getChoices().get(0);
                FinishReason finishReason = Converter.toFinishReason(choice.finishReason());
                TokenUsage tokenUsage = new TokenUsage(
                        completeResponse.getUsage().getPromptTokens(),
                        completeResponse.getUsage().getCompletionTokens(),
                        completeResponse.getUsage().getTotalTokens());

                ChatResponse chatResponse = ChatResponse.builder()
                        .aiMessage(Converter.toAiMessage(completeResponse.toAssistantMessage()))
                        .metadata(WatsonxChatResponseMetadata.builder()
                                .created(completeResponse.getCreated())
                                .createdAt(completeResponse.getCreatedAt())
                                .finishReason(finishReason)
                                .id(completeResponse.getId())
                                .modelName(completeResponse.getModelId())
                                .model(completeResponse.getModel())
                                .modelVersion(completeResponse.getModelVersion())
                                .object(completeResponse.getObject())
                                .tokenUsage(tokenUsage)
                                .build())
                        .build();

                handler.onCompleteResponse(chatResponse);
            }

            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }

            @Override
            public void onPartialResponse(String partialResponse, PartialChatResponse partialChatResponse) {
                handler.onPartialResponse(partialResponse);
            }
        });
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return this.defaultRequestParameters;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        var capatibilities = new HashSet<Capability>();
        if (enableJsonSchema
                || (nonNull(defaultRequestParameters.responseFormat())
                        && defaultRequestParameters.responseFormat().type().equals(ResponseFormatType.JSON)
                        && nonNull(defaultRequestParameters.responseFormat().jsonSchema())))
            capatibilities.add(Capability.RESPONSE_FORMAT_JSON_SCHEMA);

        return capatibilities;
    }

    @Override
    public ModelProvider provider() {
        return WATSONX;
    }

    /**
     * Returns a new {@link Builder} instance.
     * <p>
     * <b>Example usage:</b>
     *
     * <pre>{@code
     * ChatService chatService = ChatService.builder()
     *     .url("https://...") // or use CloudRegion
     *     .authenticationProvider(authProvider)
     *     .projectId("my-project-id")
     *     .modelId("ibm/granite-3-8b-instruct")
     *     .build();
     *
     * WatsonxChatRequestParameters defaultRequestParameters =
     *     WatsonxChatRequestParameters.builder()
     *         .maxOutputTokens(0)
     *         .temperature(0.7)
     *         .build();
     *
     * StreamingChatModel chatModel = WatsonxStreamingChatModel.builder()
     *     .service(chatService)
     *     .defaultRequestParameters(defaultRequestParameters)
     *     .build();
     * }</pre>
     *
     *
     * @see ChatService
     * @see WatsonxChatRequestParameters
     * @return {@link Builder} instance.
     *
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing {@link WatsonxStreamingChatModel} instances with configurable parameters.
     */
    public static class Builder extends WatsonxChat.Builder<Builder> {

        public WatsonxStreamingChatModel build() {
            return new WatsonxStreamingChatModel(this);
        }
    }
}
