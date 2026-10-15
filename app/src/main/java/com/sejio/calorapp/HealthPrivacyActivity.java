package com.sejio.calorapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * What Pausa does with the data it reads from Health Connect. Android shows this screen from the
 * Health Connect settings ("Ver política de privacidad") and requires it before granting access.
 */
public final class HealthPrivacyActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PausaUi.CREAM);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = PausaUi.dp(this, 24);
        column.setPadding(pad, pad, pad, pad);
        column.addView(PausaUi.editorial(this, "Pausa y tu salud", 28));
        String[] paragraphs = {
                "Pausa solo lee de Health Connect el sueño, los pasos, el ejercicio y el pulso en reposo. No escribe nada.",
                "Guarda un resumen por día (minutos dormidos, pasos, minutos de ejercicio y pulso en reposo) para enseñarlo en Hoy "
                        + "y en el resumen de la semana.",
                "Ese resumen se sincroniza con tu nube de Pausa, a la que solo entra tu cuenta, para verlo desde la web o desde "
                        + "tus agentes. No se comparte con nadie más.",
                "Puedes quitar el acceso cuando quieras desde los ajustes de Health Connect, y apagar la función en Ajustes → Funciones.",
        };
        for (String text : paragraphs) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = PausaUi.dp(this, 14);
            column.addView(PausaUi.text(this, text, 15, PausaUi.INK, false), params);
        }
        scroll.addView(column);
        setContentView(scroll);
    }
}
