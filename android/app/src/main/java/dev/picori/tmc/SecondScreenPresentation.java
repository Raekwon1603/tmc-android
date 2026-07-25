package dev.picori.tmc;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.widget.FrameLayout;

/**
 * Hosts the second-screen panel on Thor's secondary display. The panel is a
 * plain Canvas custom View (SecondScreenView) fed by GameStateNative — the
 * zelda3-android architecture: native supplies data, Java draws. The
 * earlier native-drawn SurfaceView path (port_second_screen.c's render
 * thread) is retired; its code remains compiled but is never driven.
 */
public class SecondScreenPresentation extends Presentation {
    public SecondScreenPresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new SecondScreenView(getContext()), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }
}
