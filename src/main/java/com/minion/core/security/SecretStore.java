package com.minion.core.security;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本机用户绑定的 AES 密钥库。密文可备份，但换 Windows 用户/电脑后不能直接解密。 */
public final class SecretStore {
    private static final Type MAP_TYPE=new TypeToken<LinkedHashMap<String,String>>(){}.getType();
    private final Path file; private final Gson gson=new Gson();
    public SecretStore(Path jarDir){this.file=jarDir.resolve(".minion").resolve("secrets.json");}
    public synchronized void set(String name,String value)throws Exception{Map<String,String> map=load();map.put(valid(name),encrypt(value));save(map);}
    public synchronized String get(String name)throws Exception{String value=load().get(valid(name));return value==null?null:decrypt(value);}
    public synchronized boolean delete(String name)throws Exception{Map<String,String> map=load();boolean existed=map.remove(valid(name))!=null;if(existed)save(map);return existed;}
    public synchronized List<String> list()throws Exception{return new ArrayList<String>(load().keySet());}
    public Path file(){return file;}
    private Map<String,String> load()throws Exception{if(!Files.exists(file))return new LinkedHashMap<String,String>();String json=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);Map<String,String> map=gson.fromJson(json,MAP_TYPE);return map==null?new LinkedHashMap<String,String>():map;}
    private void save(Map<String,String> map)throws Exception{Files.createDirectories(file.getParent());Path tmp=file.resolveSibling(file.getFileName()+".tmp");Files.write(tmp,gson.toJson(map).getBytes(StandardCharsets.UTF_8));Files.move(tmp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    private static String valid(String name){String n=name==null?"":name.trim();if(!n.matches("[A-Za-z0-9._-]{1,80}"))throw new IllegalArgumentException("密钥名称仅允许字母、数字、点、下划线和横线");return n;}
    private static String encrypt(String plain)throws Exception{byte[]iv=new byte[16];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");c.init(Cipher.ENCRYPT_MODE,key(),new IvParameterSpec(iv));byte[]data=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));byte[]all=new byte[iv.length+data.length];System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(data,0,all,iv.length,data.length);return Base64.getEncoder().encodeToString(all);}
    private static String decrypt(String raw)throws Exception{byte[]all=Base64.getDecoder().decode(raw);if(all.length<17)throw new IllegalArgumentException("密钥库数据损坏");byte[]iv=java.util.Arrays.copyOfRange(all,0,16),data=java.util.Arrays.copyOfRange(all,16,all.length);Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");c.init(Cipher.DECRYPT_MODE,key(),new IvParameterSpec(iv));return new String(c.doFinal(data),StandardCharsets.UTF_8);}
    private static SecretKeySpec key()throws Exception{String seed=System.getProperty("user.name","")+"|"+System.getProperty("user.home","")+"|"+System.getenv("COMPUTERNAME")+"|minion-secret-v1";byte[]hash=MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));return new SecretKeySpec(java.util.Arrays.copyOf(hash,16),"AES");}
}
