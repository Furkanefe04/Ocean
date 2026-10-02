package ocean.stdlib;
/**
 * HTTP isteklerinin durum kodunu (status) ve yanıt gövdesini (body) tutan kayıt (record).
 */
public record OceanHttpResponse(int status, String body) { }
