package com.sejio.calorapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * «Pausa · Cajón» in the Android share sheet: whatever is shared (a link, a text) goes straight to
 * the cajón without opening the app, also without coverage. There is no screen: it saves and closes.
 */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String text = intent == null ? null : intent.getStringExtra(Intent.EXTRA_TEXT);
        String subject = intent == null ? null : intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (subject != null && !subject.trim().isEmpty() && (text == null || !text.contains(subject.trim()))) {
            text = subject.trim() + (text == null ? "" : "\n" + text);
        }
        String id = CaptureStore.add(this, text, "share");
        Toast.makeText(this, id == null ? "No había nada que guardar." : Features.isOn(this, Features.CAPTURES)
                ? "Guardado en el cajón." : "Guardado en el cajón. Enciéndelo en Ajustes → Funciones para verlo.", Toast.LENGTH_SHORT).show();
        finish();
    }
}
