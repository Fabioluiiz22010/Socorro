package com.example.tressensores;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;


public class BoasVindasActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private TextInputLayout campoNome;
    private TextInputEditText edtNome, edtTelefone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_boas_vindas);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.boasVindas), (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom);
            return insets;
        });

        prefs = getSharedPreferences("socorro", MODE_PRIVATE);
        campoNome = findViewById(R.id.campoNome);
        edtNome = findViewById(R.id.edtNome);
        edtTelefone = findViewById(R.id.edtTelefoneInicial);

        edtNome.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                campoNome.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        edtTelefone.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                concluir();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnComecar).setOnClickListener(v -> concluir());
    }

    private void concluir() {
        String nome = edtNome.getText() == null ? "" : edtNome.getText().toString().trim();
        String telefone = edtTelefone.getText() == null ? "" : edtTelefone.getText().toString().trim();

        if (nome.length() < 2) {
            campoNome.setError("Informe seu nome para continuar");
            edtNome.requestFocus();
            return;
        }

        prefs.edit()
                .putString("usuario", nome)
                .putString("telefone", telefone)
                .apply();

        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
