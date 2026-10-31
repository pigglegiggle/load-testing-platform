package dev.apirunner;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

@ControllerAdvice
class BodyBudget extends RequestBodyAdviceAdapter {
    public boolean supports(
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    public HttpInputMessage beforeBodyRead(
            HttpInputMessage input,
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter)
            throws IOException {
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }

            public InputStream getBody() throws IOException {
                return new FilterInputStream(input.getBody()) {
                    private long read;

                    private void count(int value) throws IOException {
                        if (value > 0 && (read += value) > 2_097_152)
                            throw new IOException("Request body exceeds 2 MiB");
                    }

                    public int read() throws IOException {
                        int value = in.read();
                        count(value < 0 ? 0 : 1);
                        return value;
                    }

                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        int value = in.read(bytes, offset, Math.min(length, 8192));
                        count(value);
                        return value;
                    }
                };
            }
        };
    }
}
