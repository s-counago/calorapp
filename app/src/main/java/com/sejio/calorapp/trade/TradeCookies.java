package com.sejio.calorapp.trade;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/** Private cookie jar: never shared with WebView, other banks or other HTTP clients. */
final class TradeCookies implements CookieJar {
    private final HttpUrl origin;
    private final List<Cookie> cookies = new ArrayList<>();

    TradeCookies(HttpUrl origin) { this.origin = origin; }

    private boolean allowed(HttpUrl url) {
        return origin.scheme().equals(url.scheme()) && origin.host().equals(url.host()) && origin.port() == url.port();
    }

    @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> received) {
        if (!allowed(url)) return;
        for (Cookie cookie : received) {
            // Cookie.matches also checks path; domain validation is already done by OkHttp's parser.
            for (Iterator<Cookie> it = cookies.iterator(); it.hasNext();) {
                Cookie old = it.next();
                if (old.name().equals(cookie.name()) && old.domain().equals(cookie.domain()) && old.path().equals(cookie.path())) it.remove();
            }
            if (cookie.expiresAt() > System.currentTimeMillis() && cookies.size() < 64) cookies.add(cookie);
        }
    }

    @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> result = new ArrayList<>();
        if (!allowed(url)) return result;
        for (Iterator<Cookie> it = cookies.iterator(); it.hasNext();) {
            Cookie cookie = it.next();
            if (cookie.expiresAt() <= System.currentTimeMillis()) it.remove();
            else if (cookie.matches(url)) result.add(cookie);
        }
        return result;
    }

    synchronized void clear() { cookies.clear(); }

    synchronized JSONArray export() throws Exception {
        JSONArray result = new JSONArray();
        for (Cookie c : cookies) result.put(new JSONObject().put("name", c.name()).put("value", c.value())
                .put("domain", c.domain()).put("path", c.path()).put("expires", c.expiresAt())
                .put("hostOnly", c.hostOnly()).put("secure", c.secure()).put("httpOnly", c.httpOnly()));
        return result;
    }

    synchronized void restore(JSONArray data) throws Exception {
        if (data == null) return;
        if (data.length() > 64) throw TradeException.protocol();
        for (int i = 0; i < data.length(); i++) {
            JSONObject c = data.getJSONObject(i);
            long expires = c.getLong("expires");
            if (expires <= System.currentTimeMillis()) continue;
            Cookie.Builder b = new Cookie.Builder().name(c.getString("name")).value(c.getString("value"))
                    .path(c.getString("path")).expiresAt(expires);
            if (c.getBoolean("hostOnly")) b.hostOnlyDomain(c.getString("domain")); else b.domain(c.getString("domain"));
            if (c.getBoolean("secure")) b.secure();
            if (c.getBoolean("httpOnly")) b.httpOnly();
            Cookie cookie = b.build();
            String domain = cookie.domain();
            if (!origin.host().equals(domain) && !origin.host().endsWith("." + domain)) throw TradeException.protocol();
            cookies.add(cookie);
        }
    }
}
