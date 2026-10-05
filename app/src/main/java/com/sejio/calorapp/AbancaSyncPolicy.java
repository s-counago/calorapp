package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Closed list of observed read routes. Never synthesizes product tokens or follows page menus. */
final class AbancaSyncPolicy {
    static final String OVERVIEW = "https://bancaelectronica.abanca.com/wele200/General/Posicion/WELE200M_Posicion.aspx";
    static final int MAX_PRODUCTS = 20;
    static final class Target {
        final String type, label, kind, url;
        Target(String type, String label, String kind, String url) { this.type = type; this.label = label; this.kind = kind; this.url = url; }
    }
    static final class Plan {
        final List<Target> targets = new ArrayList<>();
        int skipped;
    }
    static String pageType(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"bancaelectronica.abanca.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return "";
            String path = uri.getRawPath().toLowerCase(Locale.ROOT);
            if (path.equals("/wele200/general/posicion/wele200m_posicion.aspx")) return uri.getRawQuery() == null ? "overview" : "";
            String query = uri.getRawQuery();
            if (query == null || !query.startsWith("k=") || query.length() <= 2 || query.length() > 4096 || query.indexOf('&') >= 0) return "";
            switch (path) {
                case "/wele200/general/consultamovimientos/wele200m_consultamovimientos_res.aspx": return "account";
                case "/wele200/tarjetas/movimientostarjeta/wele200m_movimientostarjeta_ini.aspx": return "card";
                case "/wele200/prestamos/consulta/wele200m_consultaprestamo_ini.aspx": return "loan";
                default: return "";
            }
        } catch (Exception ignored) { return ""; }
    }
    static Plan plan(JSONObject source) throws Exception {
        if (!"ready".equals(source.getString("status"))) throw new IllegalArgumentException("No se pudieron identificar los productos.");
        JSONArray targets = source.getJSONArray("targets");
        if (targets.length() > 200) throw new IllegalArgumentException("Demasiados productos.");
        Plan plan = new Plan(); HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.getJSONObject(i);
            String type = target.getString("type"), label = target.getString("label"), url = target.getString("url");
            if (type.equals("overview") || type.isEmpty() || !type.equals(pageType(url)) || label.trim().isEmpty() || label.length() > 120) {
                plan.skipped++; continue;
            }
            if (!seen.add(url)) continue;
            if (plan.targets.size() >= MAX_PRODUCTS) { plan.skipped++; continue; }
            String kind = target.optString("kind", "");
            if (kind.length() > 80) { plan.skipped++; continue; }
            plan.targets.add(new Target(type, label, kind, url));
        }
        return plan;
    }
}
