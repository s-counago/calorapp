package com.sejio.calorapp.trade;

import java.io.IOException;

/** Safe, fixed user messages. Never includes response bodies, URLs, tokens or credentials. */
public final class TradeException extends IOException {
    public final String code;

    public TradeException(String code, String message) {
        super(message);
        this.code = code;
    }

    static TradeException protocol() {
        return new TradeException("PROTOCOL", "Trade Republic ha cambiado una respuesta. La copia anterior se conserva.");
    }

    static TradeException network() {
        return new TradeException("NETWORK", "No se pudo completar la conexión. Comprueba la red y vuelve a intentarlo.");
    }
}
