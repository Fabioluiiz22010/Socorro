package com.example.tressensores;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;


public final class Estado {

    public interface Ouvinte {
        void onEstadoMudou();
    }

    public static boolean monitorando = false;
    public static String status = "Monitoramento desligado";
    public static String corStatus = "#757575";
    public static String sensores = "Sensores parados";
    public static float lux = -1f;

    public static float aceleracaoG = 0f;
    public static float rotacao = 0f;
    public static boolean noBolso = false;
    public static float pressaoHpa = 0f;
    public static double velocidadeKmh = -1;
    public static String localizacao = "aguardando GPS";

    public static String envioApi = "Nenhum envio para a API ainda";
    public static boolean envioOk = true;

    public static String clima = "Aguardando localização…";
    public static boolean climaRisco = false;

    public static boolean contagemAtiva = false;
    public static String contagemTitulo = "";
    public static String contagemMotivo = "";
    public static String contagemTextoCancelar = "Cancelar";
    public static int segundosRestantes = 0;

    public static boolean telaPrincipalVisivel = false;

    private static final List<Ouvinte> ouvintes = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORICO = 30;

    private Estado() {
    }

    public static void observar(Ouvinte o) {
        ouvintes.add(o);
    }

    public static void remover(Ouvinte o) {
        ouvintes.remove(o);
    }

    public static void notificar() {
        for (Ouvinte o : ouvintes) o.onEstadoMudou();
    }

    public static void definirStatus(String texto, String cor) {
        status = texto;
        corStatus = cor;
        notificar();
    }


    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("socorro", Context.MODE_PRIVATE);
    }

    public static void adicionarHistorico(Context ctx, String evento) {
        try {
            JSONArray antigo = new JSONArray(prefs(ctx).getString("historico", "[]"));
            JSONArray novo = new JSONArray();
            String hora = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date());
            novo.put(hora + " — " + evento);
            for (int i = 0; i < antigo.length() && novo.length() < MAX_HISTORICO; i++) novo.put(antigo.getString(i));
            prefs(ctx).edit().putString("historico", novo.toString()).apply();
        } catch (Exception ignored) {
        }
        notificar();
    }

    public static String textoHistorico(Context ctx) {
        try {
            JSONArray lista = new JSONArray(prefs(ctx).getString("historico", "[]"));
            if (lista.length() == 0) return "Nenhum evento";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lista.length(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(lista.getString(i));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Nenhum evento";
        }
    }

    public static void limparHistorico(Context ctx) {
        prefs(ctx).edit().remove("historico").apply();
        notificar();
    }
}
