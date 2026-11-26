package com.mafiizz.doomtv;

import org.libsdl.app.SDLActivity;

/** Roda o motor do Doom (doomgeneric + SDL2) em tela cheia. */
public class DoomActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "SDL2_mixer", "main" };
    }

    @Override
    protected String[] getArguments() {
        String iwad = getIntent().getStringExtra("iwad");
        String work = getIntent().getStringExtra("workdir");
        return new String[] {
            "-workdir", work,
            "-iwad", iwad,
            "-config", work + "/default.cfg",
            "-nomusic"
        };
    }
}
