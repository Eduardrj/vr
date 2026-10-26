package br.com.edsystems.lentevr;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes salvos no aparelho. Os padrões vêm da calibração no Galaxy A14 + Shinecon. */
final class Ajustes {
    static final String PREFS = "lentevr";

    /** Distância entre os centros das imagens, em mm (calibrado: 55). */
    float lentesMm = 55f;
    /** Tamanho da imagem, 1 = cabe inteira nas lentes. */
    float zoom = 1f;
    /** 0 = cinema, 1 = 3D simulado, 2 = conteúdo já lado a lado. */
    int modo = 0;
    /** Força do 3D simulado, 0..1. */
    float profundidade = 0.6f;

    // Perfil óptico (modelo do Google Cardboard; k1/k2 do perfil Cardboard V2)
    static final float TELA_LENTE_M = 0.042f;
    static final float K1 = 0.34f;
    static final float K2 = 0.55f;
    static final float FOV_MAX_GRAUS = 50f;

    static Ajustes ler(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Ajustes a = new Ajustes();
        a.lentesMm = p.getFloat("lentesMm", a.lentesMm);
        a.zoom = p.getFloat("zoom", a.zoom);
        a.modo = p.getInt("modo", a.modo);
        a.profundidade = p.getFloat("profundidade", a.profundidade);
        return a;
    }

    void salvar(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("lentesMm", lentesMm)
                .putFloat("zoom", zoom)
                .putInt("modo", modo)
                .putFloat("profundidade", profundidade)
                .apply();
    }
}
