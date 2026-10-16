package android.net;
public class Uri{
 private final String url;
 private Uri(String s){url=s;}
 public static Uri parse(String s){return new Uri(s);}
 public String getScheme(){int n=url.indexOf(':');return n<0?null:url.substring(0,n);}
 @Override public String toString(){return url;}
}
