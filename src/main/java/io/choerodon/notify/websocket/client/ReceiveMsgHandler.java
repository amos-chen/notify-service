package io.choerodon.notify.websocket.client;

import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.ParameterizedType;

public interface ReceiveMsgHandler<T> {

    void handle(WebSocketSession session, T payload);

    String matchType();

    @SuppressWarnings("unchecked")
    default Class<T> payloadClass() {
        ParameterizedType type = (ParameterizedType) this.getClass()
                .getGenericSuperclass();
        return (Class<T>) type.getActualTypeArguments()[0];
    }

}
