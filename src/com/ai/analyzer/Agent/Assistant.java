package com.ai.analyzer.Agent;

import dev.langchain4j.data.message.ChatMessage;
//import dev.langchain4j.service.AiServiceStreamingResponseHandler;
import dev.langchain4j.service.TokenStream;
import java.util.List;


//@AiServices
public interface Assistant {

    // 关键：使用 @Streaming 注解 + StreamingResponseHandler
    //@Streaming
    //void chat(List<ChatMessage> messages, StreamingResponseHandler<String> handler);
    TokenStream chat(List<ChatMessage> messages);
}
