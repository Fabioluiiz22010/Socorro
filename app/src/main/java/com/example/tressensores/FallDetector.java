package com.example.tressensores;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;


public class FallDetector implements SensorEventListener {

    public interface Listener {
        void onQuedaDetectada(float picoG, boolean houveQuedaLivre, float rotacaoMax, float quedaAltitudeM);

        void onLeituras(float aceleracaoG, float rotacao, boolean noBolso, float lux, float pressaoHpa);
    }


    private static final float QUEDA_LIVRE_G = 0.5f;
    private static final float IMPACTO_G = 2.5f;
    private static final float IMPACTO_FORTE_G = 4.0f;
    private static final float IMPACTO_SEVERO_G = 6.0f;
    private static final float ROTACAO_BRUSCA = 3.0f;
    private static final long JANELA_MS = 1000;
    private static final long INTERVALO_ENTRE_QUEDAS_MS = 10_000;
    private static final long INTERVALO_UI_MS = 200;
    private static final long JANELA_ALTITUDE_MS = 4000;

    private static class AmostraAltitude {
        final long tempo;
        final float altitude;

        AmostraAltitude(long tempo, float altitude) {
            this.tempo = tempo;
            this.altitude = altitude;
        }
    }

    private final SensorManager sensorManager;
    private final Listener listener;
    private Sensor acelerometro, giroscopio, proximidade, luz, barometro;

    private long ultimaQuedaLivre = -JANELA_MS * 10;
    private long ultimaRotacao = -JANELA_MS * 10;
    private long ultimaQueda = -INTERVALO_ENTRE_QUEDAS_MS * 10;
    private long ultimaAtualizacaoUi = 0;

    private float aceleracaoAtual = 1f;
    private float rotacaoAtual = 0f;
    private float rotacaoMax = 0f;
    private boolean noBolso = false;
    private float lux = -1f;
    private float pressao = 0f;
    private final ArrayDeque<AmostraAltitude> altitudes = new ArrayDeque<>();

    public FallDetector(Context context, Listener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
    }

    public List<String> iniciar() {
        List<String> ausentes = new ArrayList<>();
        acelerometro = registrar(Sensor.TYPE_ACCELEROMETER, "Acelerômetro", SensorManager.SENSOR_DELAY_GAME, ausentes);
        giroscopio = registrar(Sensor.TYPE_GYROSCOPE, "Giroscópio", SensorManager.SENSOR_DELAY_GAME, ausentes);
        proximidade = registrar(Sensor.TYPE_PROXIMITY, "Proximidade", SensorManager.SENSOR_DELAY_NORMAL, ausentes);
        luz = registrar(Sensor.TYPE_LIGHT, "Luminosidade", SensorManager.SENSOR_DELAY_NORMAL, ausentes);
        barometro = registrar(Sensor.TYPE_PRESSURE, "Barômetro", SensorManager.SENSOR_DELAY_NORMAL, ausentes);
        return ausentes;
    }

    public void parar() {
        sensorManager.unregisterListener(this);
        altitudes.clear();
    }

    public boolean temAcelerometro() {
        return sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
    }

    private Sensor registrar(int tipo, String nome, int atraso, List<String> ausentes) {
        Sensor s = sensorManager.getDefaultSensor(tipo);
        if (s == null) {
            ausentes.add(nome);
        } else {
            sensorManager.registerListener(this, s, atraso);
        }
        return s;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long agora = event.timestamp / 1_000_000L; // ns -> ms
        float[] v = event.values;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                processarAceleracao(v, agora);
                break;

            case Sensor.TYPE_GYROSCOPE:
                rotacaoAtual = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                if (agora - ultimaRotacao > JANELA_MS) rotacaoMax = 0f;
                if (rotacaoAtual > ROTACAO_BRUSCA) {
                    ultimaRotacao = agora;
                    rotacaoMax = Math.max(rotacaoMax, rotacaoAtual);
                }
                break;

            case Sensor.TYPE_PROXIMITY:
                 noBolso = v[0] < event.sensor.getMaximumRange();
                break;

            case Sensor.TYPE_LIGHT:
                lux = v[0];
                break;

            case Sensor.TYPE_PRESSURE:
                pressao = v[0];
                float alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressao);
                altitudes.addLast(new AmostraAltitude(agora, alt));
                while (!altitudes.isEmpty() && agora - altitudes.peekFirst().tempo > JANELA_ALTITUDE_MS) {
                    altitudes.removeFirst();
                }
                break;
        }

        if (agora - ultimaAtualizacaoUi > INTERVALO_UI_MS) {
            ultimaAtualizacaoUi = agora;
            listener.onLeituras(aceleracaoAtual, rotacaoAtual, noBolso, lux, pressao);
        }
    }

    private void processarAceleracao(float[] v, long agora) {
        float g = (float) (Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / SensorManager.GRAVITY_EARTH);
        aceleracaoAtual = g;

        if (g < QUEDA_LIVRE_G) ultimaQuedaLivre = agora;

        if (g < IMPACTO_G || agora - ultimaQueda < INTERVALO_ENTRE_QUEDAS_MS) return;

        boolean houveQuedaLivre = agora - ultimaQuedaLivre < JANELA_MS;
        boolean houveRotacao = agora - ultimaRotacao < JANELA_MS;

        int pontos = 0;
        if (houveQuedaLivre) pontos++;
        if (houveRotacao) pontos++;
        if (g >= IMPACTO_FORTE_G) pontos++;
        if (g >= IMPACTO_SEVERO_G) pontos++;

        // Filtro de falso positivo com o sensor de proximidade
        boolean naMao = proximidade != null && !noBolso;
        int necessario = (naMao && !houveQuedaLivre) ? 3 : 2;

        if (pontos >= necessario) {
            ultimaQueda = agora;
            listener.onQuedaDetectada(g, houveQuedaLivre, houveRotacao ? rotacaoMax : 0f, quedaDeAltitude());
        }
    }

     private float quedaDeAltitude() {
        if (altitudes.size() < 2) return 0f;
        float max = -Float.MAX_VALUE;
        for (AmostraAltitude a : altitudes) max = Math.max(max, a.altitude);
        return Math.max(0f, max - altitudes.peekLast().altitude);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
