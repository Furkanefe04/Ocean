package ocean.stdlib;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
/**
 * Senkron ve asenkron HTTP/REST istekleri (GET, POST vb.) gerçekleştiren standart HTTP istemci sınıfı.
 */
public class OceanHttp {
    private static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static String get(String url) {
        return request("GET", url, null, null).body();
    }

    public static String post(String url, String body) {
        return request("POST", url, body, Map.of("Content-Type", "application/json")).body();
    }

    public static String put(String url, String body) {
        return request("PUT", url, body, Map.of("Content-Type", "application/json")).body();
    }

    public static String delete(String url) {
        return request("DELETE", url, null, null).body();
    }

    public static String patch(String url, String body) {
        return request("PATCH", url, body, Map.of("Content-Type", "application/json")).body();
    }

    public static java.util.concurrent.CompletableFuture<OceanHttpResponse> getAsync(String url) {
        return requestAsync("GET", url, null, null);
    }

    public static java.util.concurrent.CompletableFuture<OceanHttpResponse> postAsync(String url, String body) {
        return requestAsync("POST", url, body, Map.of("Content-Type", "application/json"));
    }

    public static CompletableFuture<OceanHttpResponse> requestAsync(String method, String url, String body, Map<String, String> headers) {
        try {
            HttpRequest request = createRequest(method, url, body, headers);
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> new OceanHttpResponse(r.statusCode(), r.body()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static HttpRequest createRequest(String method, String url, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10));

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            publisher = HttpRequest.BodyPublishers.ofString(body);
        }

        builder.method(method, publisher);
        return builder.build();
    }

    public static OceanHttpResponse request(String method, String url, String body, Map<String, String> headers) {
        try {
            HttpRequest request = createRequest(method, url, body, headers);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new OceanHttpResponse(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        }
    }
}
