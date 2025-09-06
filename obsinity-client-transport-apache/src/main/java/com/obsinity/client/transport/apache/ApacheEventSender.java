package com.obsinity.client.transport.apache;

import com.obsinity.client.transport.EventSender;
import java.io.IOException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;

/** Default Apache HttpClient 5 sender with zero-config endpoint selection. */
public class ApacheEventSender implements EventSender {
    private final CloseableHttpClient client = HttpClients.createDefault();

    @Override
    public void send(byte[] body) throws IOException {
        HttpPost post = new HttpPost(endpoint());
        post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        client.execute(post, response -> {
            response.close();
            return null;
        });
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
