package com.sejio.calorapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class QuickCaptureTest {
    /** Thursday: 4 (0 = domingo). */
    private static final int THURSDAY = 4;

    private static QuickCapture.Parsed parse(String line) {
        return QuickCapture.parse(line, "inbox", THURSDAY);
    }

    @Test public void plainLineGoesToTheInbox() {
        QuickCapture.Parsed p = parse("poner lavadora");
        assertEquals("task", p.kind);
        assertEquals("poner lavadora", p.title);
        assertEquals("inbox", p.lane);
        assertNull(p.day);
        assertNull(p.slot);
    }

    @Test public void dayAndSlotAtTheEnd() {
        QuickCapture.Parsed p = parse("llamar al banco mañana tarde");
        assertEquals("llamar al banco", p.title);
        assertEquals(Integer.valueOf(1), p.day);
        assertEquals("afternoon", p.slot);

        p = parse("revisar vídeo hoy por la noche");
        assertEquals("revisar vídeo", p.title);
        assertEquals(Integer.valueOf(0), p.day);
        assertEquals("evening", p.slot);

        p = parse("correr mañana por la mañana");
        assertEquals("correr", p.title);
        assertEquals(Integer.valueOf(1), p.day);
        assertEquals("morning", p.slot);

        p = parse("dentista pasado mañana");
        assertEquals("dentista", p.title);
        assertEquals(Integer.valueOf(2), p.day);
        assertNull(p.slot);
    }

    @Test public void weekdaysCountFromToday() {
        QuickCapture.Parsed p = parse("cena con Ana el sábado noche");
        assertEquals("cena con Ana", p.title);
        assertEquals(Integer.valueOf(2), p.day);
        assertEquals("evening", p.slot);
        assertEquals(Integer.valueOf(5), parse("pagar alquiler martes").day);
        assertEquals(Integer.valueOf(0), parse("regar jueves").day);
        assertEquals(Integer.valueOf(6), parse("tirar basura el miercoles").day);
    }

    @Test public void wordsInsideTheTitleStay() {
        assertEquals("preparar la cena de mañana para Ana", parse("preparar la cena de mañana para Ana").title);
        QuickCapture.Parsed p = parse("no llegar tarde");
        assertEquals("no llegar tarde", p.title);
        assertNull(p.day);
        assertNull(p.slot);
        // A line that is only a day word is a task with that title.
        assertEquals("mañana", parse("mañana").title);
    }

    @Test public void spelledSlotAloneMeansToday() {
        QuickCapture.Parsed p = parse("estirar por la tarde");
        assertEquals("estirar", p.title);
        assertEquals(Integer.valueOf(0), p.day);
        assertEquals("afternoon", p.slot);
    }

    @Test public void lanes() {
        QuickCapture.Parsed p = parse("!comprar regalo madre");
        assertEquals("priority", p.lane);
        assertEquals("comprar regalo madre", p.title);
        assertEquals("waiting", parse("esperar factura de Juan").lane);
        assertEquals("stack", QuickCapture.parse("aprender a tocar el piano", "stack", THURSDAY).lane);
    }

    @Test public void counters() {
        QuickCapture.Parsed p = parse("+350");
        assertEquals("calories", p.kind);
        assertEquals(350, p.amount);
        assertEquals("protein", parse("p30").kind);
        assertEquals(30, parse("P 30 g").amount);
        assertEquals("cigarettes", parse("c1").kind);
        assertEquals("task", parse("comprar 2 litros").kind);
    }
}
