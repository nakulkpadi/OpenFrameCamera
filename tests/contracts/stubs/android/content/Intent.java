package android.content;
import java.util.HashMap;
public class Intent {
 private final HashMap<String,Object> extras=new HashMap<>();
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
 public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
 public String getStringExtra(String k){return (String)extras.get(k);}
 public boolean getBooleanExtra(String k,boolean d){Object v=extras.get(k);return v instanceof Boolean?(Boolean)v:d;}
 public int getIntExtra(String k,int d){Object v=extras.get(k);return v instanceof Integer?(Integer)v:d;}
 public boolean hasExtra(String k){return extras.containsKey(k);}
}
