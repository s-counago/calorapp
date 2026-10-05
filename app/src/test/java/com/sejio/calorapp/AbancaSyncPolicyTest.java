package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AbancaSyncPolicyTest {
    private static final String ACCOUNT = "https://bancaelectronica.abanca.com/wele200/General/ConsultaMovimientos/WELE200M_ConsultaMovimientos_Res.aspx?k=FAKE";
    @Test public void onlyObservedReadDestinationsAreAccepted() {
        assertEquals("account", AbancaSyncPolicy.pageType(ACCOUNT));
        assertEquals("overview", AbancaSyncPolicy.pageType(AbancaSyncPolicy.OVERVIEW));
        for (String url : new String[]{ACCOUNT.replace("https:", "http:"), ACCOUNT.replace(".com/", ".com.evil.test/"),
                ACCOUNT.replace("https://", "https://user@"), ACCOUNT.replace(".com/", ".com:8443/"),
                ACCOUNT + "&op=0", ACCOUNT + "#fragment", ACCOUNT.replace("ConsultaMovimientos_Res", "Traspaso_Ini"),
                ACCOUNT.replace("?k=FAKE", "?k="), ACCOUNT.replace("/General/", "/General/../General/"), "javascript:alert(1)"})
            assertEquals("", AbancaSyncPolicy.pageType(url));
    }
    @Test public void plansAreBoundedAndDeduplicateOnlyTheSameNavigation() throws Exception {
        JSONArray targets = new JSONArray();
        for (int i = 0; i < 23; i++) targets.put(new JSONObject().put("type", "account").put("label", "Cuenta " + i).put("url", ACCOUNT + i));
        targets.put(new JSONObject().put("type", "account").put("label", "Otra etiqueta").put("url", ACCOUNT + 0));
        targets.put(new JSONObject().put("type", "loan").put("label", "Tipo equivocado").put("url", ACCOUNT));
        AbancaSyncPolicy.Plan plan = AbancaSyncPolicy.plan(new JSONObject().put("status", "ready").put("targets", targets));
        assertEquals(20, plan.targets.size()); assertEquals(4, plan.skipped);
    }
}
