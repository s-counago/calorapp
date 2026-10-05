package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AbancaCaptureTest {
    private JSONObject cell(String text) throws Exception {
        return new JSONObject().put("column", 0).put("text", text).put("colspan", 1).put("header", false).put("href", "DO_NOT_KEEP_TOKEN");
    }
    private JSONObject source() throws Exception {
        JSONArray rows = new JSONArray().put(new JSONObject().put("index", 0).put("cells", new JSONArray().put(cell("F.OPERAC."))))
                .put(new JSONObject().put("index", 1).put("cells", new JSONArray().put(cell("05/10/2026"))));
        return new JSONObject().put("status", "no_records").put("pageType", "account").put("sourceTruncated", false)
                .put("omittedRows", 1).put("sourceTables", new JSONArray().put(new JSONObject().put("key", "movements").put("title", "Movimientos").put("rows", rows)));
    }
    @Test public void sourceOnlyRowsRemainAvailableWithoutInventingZeroOrAuthentication() throws Exception {
        JSONObject capture = AbancaCapture.fromPage("Cuenta ficticia", "Corriente",
                "https://bancaelectronica.abanca.com/wele200/General/ConsultaMovimientos/WELE200M_ConsultaMovimientos_Res.aspx?k=PRIVATE_TOKEN", source(), 123);
        assertEquals("source_only", capture.getString("status")); assertTrue(capture.isNull("normalized"));
        assertEquals(1, capture.getInt("omittedRows"));
        assertEquals(2, capture.getJSONArray("sourceTables").getJSONObject(0).getJSONArray("rows").length());
        assertFalse(capture.toString().contains("PRIVATE_TOKEN")); assertFalse(capture.toString().contains("DO_NOT_KEEP_TOKEN"));
        assertFalse(capture.getString("sourcePath").contains("?"));
    }
    @Test public void mismatchedProductPagesCannotBeStoredUnderAnotherProductType() throws Exception {
        try {
            AbancaCapture.fromPage("Tarjeta", "", "https://bancaelectronica.abanca.com/wele200/Tarjetas/MovimientosTarjeta/WELE200M_MovimientosTarjeta_Ini.aspx?k=FAKE", source(), 123);
            fail();
        } catch (IllegalArgumentException expected) { }
    }
    @Test public void malformedSourceCoordinatesAreRejected() throws Exception {
        JSONObject raw = source(); raw.getJSONArray("sourceTables").getJSONObject(0).getJSONArray("rows").getJSONObject(1).put("index", 0);
        try { AbancaCapture.sourceTables(raw.getJSONArray("sourceTables")); fail(); } catch (IllegalArgumentException expected) { }
    }
}
