package io.netty.channel.kqueue;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * @author kyushu
 * @date 2020/4/5 21:44
 * @description
 */
public class CodeTotal {
    public static void main(String[] args) throws Exception {
        long count = Files.walk(Paths.get("/Users/kyushu/code/source/netty/"))    // 递归获得项目目录下的所有文件
                .filter(file -> !Files.isDirectory(file))   // 筛选出文件
                .filter(file -> file.toString().endsWith(".java"))  // 筛选出 java 文件
//                .flatMap(Try.of(file -> Files.lines(file), Stream.empty()))     // 将会抛出受检异常的 Lambda 包装为 抛出非受检异常的 Lambda
                .filter(line -> !line.toString().trim().isEmpty())         // 过滤掉空行
                .filter(line -> !line.toString().trim().startsWith("//"))  //过滤掉 //之类的注释
                .filter(line -> !(line.toString().trim().startsWith("/*") && line.toString().trim().endsWith("*/")))  //过滤掉/* */之类的注释
                .filter(line -> !(line.toString().trim().startsWith("/*") && !line.toString().trim().endsWith("*/")))     //过滤掉以 /* 开头的注释（去除空格后的开头）
                .filter(line -> !(!line.toString().trim().startsWith("/*") && line.toString().trim().endsWith("*/")))     //过滤掉已 */ 结尾的注释
                .filter(line -> !line.toString().trim().startsWith("*"))   //过滤掉 javadoc 中的文字注释
                .filter(line -> !line.toString().trim().startsWith("@Override"))   //过滤掉方法上含 @Override 的
                .count();
        System.out.println("代码行数：" + count);
    }
}
