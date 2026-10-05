package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class BankingRecordsTest {
    private JSONObject movement() throws Exception {
        return new JSONObject().put("operationDate", "2026-10-05").put("valueDate", "2026-10-06").put("description", "Tienda ficticia")
                .put("operationType", "Compra").put("amount", new JSONObject().put("amount", "-12.50").put("currency", "EUR"))
                .put("balance", new JSONObject().put("amount", "100.00").put("currency", "EUR"));
    }
    @Test public void provisionalIdentityUsesProductDateAmountCurrencyAndDescription() throws Exception {
        JSONObject first = movement(); String original = BankingRecords.movementId("card-a", first);
        assertEquals(original, BankingRecords.movementId("card-a", new JSONObject(first.toString()).put("status", "Contabilizado")));
        assertEquals(original, BankingRecords.movementId("card-a", new JSONObject(first.toString()).put("balance", JSONObject.NULL)));
        assertEquals(original, BankingRecords.movementId("card-a", new JSONObject(first.toString()).put("description", "  TIENDA   FICTICIA ")));
        assertNotEquals(original, BankingRecords.movementId("card-b", first));
        assertNotEquals(original, BankingRecords.movementId("card-a", new JSONObject(first.toString()).put("operationDate", "2026-10-06")));
        assertNotEquals(original, BankingRecords.movementId("card-a", new JSONObject(first.toString()).put("description", "Otro negocio")));
        first.getJSONObject("amount").put("currency", "USD"); assertNotEquals(original, BankingRecords.movementId("card-a", first));
    }
    @Test public void exactDecimalIdentityDoesNotDependOnScale() throws Exception {
        JSONObject a = movement(), b = movement(); b.getJSONObject("amount").put("amount", "-12.5000");
        assertEquals(BankingRecords.movementId("card", a), BankingRecords.movementId("card", b));
    }
    @Test public void matchingRowsHaveSameEntityButKeepSeparateSourceObservations() throws Exception {
        JSONObject page = new JSONObject().put("product", new JSONObject().put("id", "account-a"))
                .put("normalized", new JSONObject().put("pageType", "account").put("records", new JSONArray().put(movement()).put(movement())));
        List<BankingRecords.Entity> entities = BankingRecords.abanca(page);
        assertEquals(2, entities.size()); assertEquals(entities.get(0).id, entities.get(1).id);
        assertNotEquals(entities.get(0).index, entities.get(1).index);
    }
    @Test public void originalFullDescriptionIsUsedInsteadOfTruncatedPreview() throws Exception {
        JSONObject record = movement().put("sourceTable", 0).put("sourceRow", 1).put("description", "Abreviado…");
        JSONArray cells = new JSONArray().put(new JSONObject().put("column", 2).put("text", "Concepto completo ficticio"));
        JSONObject page = new JSONObject().put("product", new JSONObject().put("id", "account-a"))
                .put("normalized", new JSONObject().put("pageType", "account").put("records", new JSONArray().put(record)))
                .put("sourceTables", new JSONArray().put(new JSONObject().put("rows", new JSONArray().put(new JSONObject().put("index", 1).put("cells", cells)))));
        BankingRecords.Entity entity = BankingRecords.abanca(page).get(0);
        assertEquals("Concepto completo ficticio", entity.data.getString("description"));
        assertEquals(0, entity.sourceTable); assertEquals(1, entity.sourceRow);
    }
    @Test public void bankIdsAndSourcesRemainSeparate() throws Exception {
        JSONObject data = new JSONObject().put("balances", new JSONArray().put(new JSONObject().put("amount", "0.00").put("currency", "EUR")))
                .put("transactions", new JSONObject().put("items", new JSONArray().put(new JSONObject().put("id", "bank-movement-1")
                        .put("title", "Tienda ficticia").put("timestamp", "2026-10-05T12:30:00Z")
                        .put("amount", new JSONObject().put("value", "-12.5").put("currency", "EUR")))))
                .put("positions", new JSONArray().put(new JSONObject().put("instrumentId", "TEST_ISIN").put("quantity", "0.123456789")));
        JSONObject capture = new JSONObject().put("product", new JSONObject().put("id", "trade-account")).put("data", data);
        List<BankingRecords.Entity> entities = BankingRecords.trade(capture);
        assertEquals(3, entities.size()); assertEquals("balance", entities.get(0).type); assertEquals("movement", entities.get(1).type);
        assertEquals("0.00", entities.get(0).data.getJSONObject("balance").getString("amount"));
        assertEquals("investment_position", entities.get(2).type);
        assertEquals("-12.5", entities.get(1).data.getJSONObject("amount").getString("amount"));
        assertEquals("0.123456789", entities.get(2).data.getString("quantity"));
        assertNotEquals(BankingRecords.productId("abanca", "account", "A"), BankingRecords.productId("trade_republic", "account", "A"));
    }
}
