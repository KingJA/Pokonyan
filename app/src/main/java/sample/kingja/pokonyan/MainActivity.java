package sample.kingja.pokonyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Set<String>stringSet=new HashSet<>();
        for (int i = 0; i < 3; i++) {
            stringSet.add("item"+i);
        }
        //获取SharedPreferences
        SharedPreferences sp = getSharedPreferences("sp_pokonyan", Context.MODE_PRIVATE);
        //获取Editor
        SharedPreferences.Editor edit = sp.edit();
        //编辑数据
        edit = edit.putInt("Int", 1);
        edit = edit.putFloat("Float", 3.1415926f);
        edit = edit.putLong("Long", 10086);
        edit = edit.putBoolean("Boolean",true);
        edit = edit.putString("String", "pokonyan");
        edit = edit.putStringSet("StringSet", stringSet);
        //提交数据
        edit.apply();
        new Handler(Looper.getMainLooper());
    }


}
