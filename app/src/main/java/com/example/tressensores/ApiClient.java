package com.example.tressensores;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;


 // API Socorro https://ideaberta.com.br/socorro/
 //Clima: Open-Meteo

 //Se a conexão segura falhar o app tenta de novo com TLS 1.2 e passa a usar TLS 1.2 dali em diante.

public final class ApiClient {

    private static final String TAG = "ApiClient";
    public static final String URL_API = "https://ideaberta.com.br/socorro/";
    private static final String URL_CLIMA =
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,precipitation,wind_speed_10m,weather_code";

    public interface Callback {
        void onResposta(boolean sucesso, int codigoHttp, String corpo);
    }

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static volatile boolean usarTls12 = false;

    private ApiClient() {
    }

    public static void enviarRegistro(JSONObject dados, Callback cb) {
        executor.execute(() -> {
            Resposta r = requisitar("POST", URL_API, dados.toString());
            main.post(() -> cb.onResposta(r.sucesso(), r.codigo, r.corpo));
        });
    }

    public static final class Clima {
        public final double temperatura, chuva, vento;
        public final int codigo;

        Clima(double temperatura, double chuva, double vento, int codigo) {
            this.temperatura = temperatura;
            this.chuva = chuva;
            this.vento = vento;
            this.codigo = codigo;
        }

        public String descricao() {
            if (codigo == 0) return "céu limpo";
            if (codigo <= 3) return "parcialmente nublado";
            if (codigo == 45 || codigo == 48) return "neblina";
            if (codigo <= 57) return "garoa";
            if (codigo <= 67) return "chuva";
            if (codigo <= 77) return "neve";
            if (codigo <= 82) return "pancadas de chuva";
            return "tempestade";
        }

        public String riscos() {
            StringBuilder r = new StringBuilder();
            if (codigo >= 95) r.append("\n• Tempestade com raios");
            else if (codigo >= 51 || chuva > 0.2) r.append("\n• Chuva: pista escorregadia");
            if (codigo == 45 || codigo == 48) r.append("\n• Neblina: visibilidade baixa");
            if (vento >= 40) r.append("\n• Vento forte");
            return r.toString();
        }

        public String texto() {
            String t = String.format(Locale.getDefault(), "%.1f °C · %s · vento %.0f km/h",
                    temperatura, descricao(), vento);
            String r = riscos();
            return r.isEmpty() ? t + "\nCondições seguras para sair." : t + "\n\nATENÇÃO, condição de risco:" + r;
        }
    }

    public interface CallbackClima {
        void onClima(Clima clima, String erro);
    }

    public static void buscarClima(double lat, double lon, CallbackClima cb) {
        String url = String.format(Locale.US, URL_CLIMA, lat, lon);
        executor.execute(() -> {
            Resposta r = requisitar("GET", url, null);
            Clima clima = null;
            String erro = null;
            if (r.sucesso()) {
                try {
                    JSONObject atual = new JSONObject(r.corpo).getJSONObject("current");
                    clima = new Clima(atual.getDouble("temperature_2m"),
                            atual.optDouble("precipitation", 0),
                            atual.optDouble("wind_speed_10m", 0),
                            atual.optInt("weather_code", 0));
                } catch (Exception e) {
                    erro = "Resposta de clima inválida";
                }
            } else {
                erro = "Não foi possível consultar o clima (" + r.corpo + ")";
            }
            Clima resultado = clima;
            String mensagem = erro;
            main.post(() -> cb.onClima(resultado, mensagem));
        });
    }

    private static class Resposta {
        final int codigo;
        final String corpo;
        final boolean erroSsl;

        Resposta(int codigo, String corpo, boolean erroSsl) {
            this.codigo = codigo;
            this.corpo = corpo;
            this.erroSsl = erroSsl;
        }

        boolean sucesso() {
            return codigo >= 200 && codigo < 300;
        }
    }

    private static Resposta requisitar(String metodo, String endereco, String json) {
        Resposta r = tentar(metodo, endereco, json, usarTls12);
        if (r.erroSsl && !usarTls12) {
            Log.w(TAG, "Falha SSL, tentando de novo com TLS 1.2");
            Resposta comTls12 = tentar(metodo, endereco, json, true);
            if (!comTls12.erroSsl) usarTls12 = true;
            return comTls12;
        }
        return r;
    }

    private static Resposta tentar(String metodo, String endereco, String json, boolean forcarTls12) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(endereco).openConnection();
            if (forcarTls12 && con instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) con;
                https.setSSLSocketFactory(new Tls12SocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory()));
            }
            con.setRequestMethod(metodo);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("Accept", "application/json");

            if (json != null) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }

            int codigo = con.getResponseCode();
            InputStream is = codigo >= 400 ? con.getErrorStream() : con.getInputStream();
            return new Resposta(codigo, ler(is), false);
        } catch (SSLException e) {
            Log.e(TAG, "Erro SSL", e);
            return new Resposta(-1, "falha na conexão segura", true);
        } catch (UnknownHostException e) {
            return new Resposta(-1, "sem internet", false);
        } catch (SocketTimeoutException e) {
            return new Resposta(-1, "servidor demorou para responder", false);
        } catch (Exception e) {
            Log.e(TAG, "Erro de conexão", e);
            return new Resposta(-1, "erro de conexão", false);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String ler(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = br.readLine()) != null) sb.append(linha);
        }
        return sb.toString();
    }

    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory base;

        Tls12SocketFactory(SSLSocketFactory base) {
            this.base = base;
        }

        private Socket somenteTls12(Socket s) {
            if (s instanceof SSLSocket) ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2"});
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return base.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return base.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return somenteTls12(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            return somenteTls12(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws java.io.IOException {
            return somenteTls12(base.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return somenteTls12(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws java.io.IOException {
            return somenteTls12(base.createSocket(address, port, localAddress, localPort));
        }
    }
}