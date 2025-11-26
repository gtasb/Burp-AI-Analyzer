package com.ai.analyzer.model;

import com.ai.analyzer.utils.HttpFormatter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
public class RequestData {
    private int id;
    private String method;
    private String url;
    private String request;
    private String response;
    private LocalDateTime timestamp;

    private boolean hasResponse;

    public RequestData(int id, String method, String url, String request) {
        this.id = id;
        this.method = method;
        this.url = url;
        this.request = request;
        this.response = null;
        this.timestamp = LocalDateTime.now();
        this.hasResponse = false;
    }
    public RequestData(int id, String method, String url, String request, String response) {
        this.id = id;
        this.method = method;
        this.url = url;
        this.request = request;
        this.response = response;
        this.timestamp = LocalDateTime.now();
        this.hasResponse = response != null && !response.trim().isEmpty();
    }

    // Getters
    public int getId() { return id; }
    public String getMethod() { return method; }
    public String getUrl() { return url; }
    public String getRequest() { return request; }
    public String getResponse() { return response; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public boolean hasResponse() { return hasResponse; }

    // Setters
    public void setId(int id) { this.id = id; }
    public void setMethod(String method) { this.method = method; }
    public void setUrl(String url) { this.url = url; }
    public void setRequest(String request) { this.request = request; }
    public void setResponse(String response) { 
        this.response = response; 
        this.hasResponse = response != null && !response.trim().isEmpty();
    }

    public String getDisplayText() {
        String timeStr = timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String responseInfo = hasResponse ? " [有响应]" : " [仅请求]";
        return String.format("#%d %s %s - %s%s", id, method, url, timeStr, responseInfo);
    }

    public String getFullRequestResponse() {
        return HttpFormatter.formatHttpRequestResponse(request, response);
    }

    @Override
    public String toString() {
        return getDisplayText();
    }
}
