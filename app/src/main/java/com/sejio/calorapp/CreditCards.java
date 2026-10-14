package com.sejio.calorapp;

final class CreditCards {
    static int collectionDay(int purchaseDay) {
        int[] date = Ledger.civil(purchaseDay);
        int month = date[1] + 2, year = date[0];
        if (month > 12) { month -= 12; year++; }
        return Ledger.epochDay(year, month, 1) - 1;
    }

    static boolean isCredit(Ledger.Txn txn, Budget.Settings settings) {
        if (!txn.source.equals("card")) return false;
        String choice = settings.cardTypes.get(txn.productId);
        if (choice != null) return choice.equals("credit");
        String kind = Ledger.key(txn.productKind + " " + txn.sourceLabel);
        return kind.contains("CREDITO") || kind.contains("CREDIT");
    }
}
