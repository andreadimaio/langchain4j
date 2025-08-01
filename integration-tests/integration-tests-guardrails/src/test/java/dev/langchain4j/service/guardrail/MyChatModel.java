package dev.langchain4j.service.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.concurrent.atomic.AtomicInteger;

public class MyChatModel implements ChatModel {
    private final AtomicInteger spy = new AtomicInteger(0);

    private static String getUserMessage(ChatRequest chatRequest) {
        return chatRequest.messages().stream()
                .filter(message -> message.type() == ChatMessageType.USER)
                .findFirst()
                .map(chatMessage -> ((dev.langchain4j.data.message.UserMessage) chatMessage).singleText())
                .orElseThrow(() -> new IllegalArgumentException("No user message found"));
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        spy.incrementAndGet();

        return ChatResponse.builder()
                .aiMessage(AiMessage.from("Request: %s; Response: Hi!".formatted(getUserMessage(chatRequest))))
                .build();
    }

    public int spy() {
        return spy.get();
    }
}
