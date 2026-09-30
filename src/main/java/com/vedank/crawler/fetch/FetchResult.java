package com.vedank.crawler.fetch;

/**
 * Outcome of fetching one URL, after retries.
 *
 * @param statusCode  final HTTP status, or -1 if no response was ever received
 * @param finalUrl    URL that actually served the response (differs after redirects)
 * @param body        response body when requested and textual, otherwise empty
 * @param attempts    requests made, including retries
 * @param error       network/timeout error description when statusCode is -1
 */
public record FetchResult(
        int statusCode,
        String finalUrl,
        String contentType,
        String body,
        int attempts,
        long elapsedMillis,
        String error) {

    public boolean isHtml() {
        return contentType != null && contentType.toLowerCase().contains("text/html");
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
