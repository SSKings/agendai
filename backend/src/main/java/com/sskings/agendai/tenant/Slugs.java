package com.sskings.agendai.tenant;

import com.sskings.agendai.common.Folding;

/** Link público a partir do nome: "Barbearia São João" → "barbearia-sao-joao". */
final class Slugs {

    static final int MAX = 50;

    private Slugs() {}

    static String of(String name) {
        String s = Folding.fold(name).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAX) s = s.substring(0, MAX).replaceAll("-+$", "");
        return s.isEmpty() ? "negocio" : s;
    }
}
