package com.github.niefy.web.servlet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.niefy.web.annotation.Autowired;
import com.github.niefy.web.annotation.Component;
import com.github.niefy.web.annotation.RequestMapping;
import com.github.niefy.web.annotation.RestController;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.util.*;

public class DispatcherServlet extends HttpServlet {
    Logger log = LogManager.getLogger();
    ObjectMapper mapper = new ObjectMapper();
    private Properties properties = new Properties();
    private List<String> beanNames = new ArrayList<>();
    private Map<String, Object> ioc = new HashMap<>();
    private Map<String, Method> urlMapping = new HashMap<>();

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        super.service(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req,resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatcherServlet(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        //加载配置
        loadConfig(config.getInitParameter("contextConfigLocation"));
        //扫描要加载的类
        doScanner(properties.getProperty("scanner.package"));
        //实例化要加载的类
        doInstance();
        //加载依赖注入，给属性赋值
        doAutowired();
        //加载映射地址
        doRequestMapping();
    }

    void loadConfig(String contextConfigLocation) {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation)){
            properties.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void doScanner(String dirpath) {
        URL url = this.getClass().getClassLoader().getResource("/" + dirpath.replaceAll("\\.", "/"));
        if(url==null) return;
        File dir = new File(url.getFile());
        File[] files = dir.listFiles();
        if(files==null || files.length==0) return;
        for (File file : files) {
            if (file.isDirectory()) {
                doScanner(dirpath + "." + file.getName());
                continue;
            }

            //取文件名
            String beanName = dirpath + "." + file.getName().replaceAll(".class", "");
            beanNames.add(beanName);
        }
    }
    private String getJson(HttpServletRequest req) {
        String param = null;
        try {
            BufferedReader streamReader = new BufferedReader(new InputStreamReader(req.getInputStream(), "UTF-8"));
            StringBuilder responseStrBuilder = new StringBuilder();
            String inputStr;
            while ((inputStr = streamReader.readLine()) != null) {
                responseStrBuilder.append(inputStr);
            }
            param = responseStrBuilder.toString();
            System.out.println("request param="+param);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return param;
    }

    void doDispatcherServlet(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String url = req.getRequestURI();
        url = url.replace(req.getContextPath(), "").replaceAll("/+", "/");
        if (!urlMapping.containsKey(url)) {
            resp.getWriter().write("404! url is not found!");
            return;
        }

        Method method = urlMapping.get(url);
        String className = method.getDeclaringClass().getSimpleName();
        className = firstLowerCase(className);
        if (!ioc.containsKey(className)) {
            resp.getWriter().write("500! claas not defind !");
            return;
        }
        Object[] args ;
        if ("POST".equals(req.getMethod()) && req.getContentType().contains("json")) {
            String str = getJson(req);
            args = getRequestBody(str, method);
        } else {
            args = getRequestParam(req.getParameterMap(), method);
        }
        //调用目标方法
        Object res = method.invoke(ioc.get(className), args);

        resp.setContentType("text/html;charset=utf-8");
        resp.getWriter().write(res.toString());
    }

    Object[] getRequestBody(String json, Method method) throws JsonProcessingException {
        if (null == json || json.isEmpty()) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        Object[] requestParam = new Object[parameters.length];
        int i = 0;
        for (Parameter p : parameters) {
            Object val = mapper.readValue(json,p.getType());
            requestParam[i] = val;
            i++;
        }
        return requestParam;
    }

    Object[] getRequestParam(Map<String, String[]> map, Method method) {
        if (null == map || map.size() == 0) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        int i = 0;
        Object[] requestParam = new Object[parameters.length];
        for (Parameter p : parameters) {
            if (!map.containsKey(p.getName())) {
                requestParam[i] = null;
                i++;
                continue;
            }
            try {
                Class typeClass = p.getType();
                String[] val = map.get(p.getName());
                if (null == val) {
                    requestParam[i] = null;
                    i++;
                    continue;
                }
                Constructor con = null;
                try {
                    con = typeClass.getConstructor(val[0].getClass());
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                }
                Object obj = null;
                try {
                    obj = con.newInstance(val[0]);
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
                requestParam[i] = obj;
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }


            i++;
        }
        return requestParam;
    }

    void doRequestMapping() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> obj : ioc.entrySet()) {
            if (!obj.getValue().getClass().isAnnotationPresent(RestController.class)) {
                continue;
            }
            Method[] methods = obj.getValue().getClass().getMethods();
            for (Method method : methods) {
                if (!method.isAnnotationPresent(RequestMapping.class)) {
                    continue;
                }
                String baseUrl = "";
                if (obj.getValue().getClass().isAnnotationPresent(RequestMapping.class)) {
                    baseUrl = obj.getValue().getClass().getAnnotation(RequestMapping.class).value();
                }
                RequestMapping jcRequestMapping = method.getAnnotation(RequestMapping.class);
                if ("".equals(jcRequestMapping.value())) {
                    continue;
                }
                String url = (baseUrl + "/" + jcRequestMapping.value()).replaceAll("/+", "/");
                urlMapping.put(url, method);
                System.out.println(url);
            }
        }
    }

    void doAutowired() {
        for (Map.Entry<String, Object> obj : ioc.entrySet()) {
            try {
                for (Field field : obj.getValue().getClass().getDeclaredFields()) {
                    if (!field.isAnnotationPresent(Autowired.class)) {
                        continue;
                    }
                    Autowired autowired = field.getAnnotation(Autowired.class);
                    String beanName = autowired.value();
                    if ("".equals(beanName)) {
                        beanName = field.getType().getSimpleName();
                    }

                    field.setAccessible(true);

                    field.set(obj.getValue(), ioc.get(firstLowerCase(beanName)));
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }

        }


    }

    void doInstance() {
        if (beanNames.isEmpty()) {
            return;
        }
        for (String beanName : beanNames) {
            try {
                Class<?> cls = Class.forName(beanName);
                if (cls.isAnnotationPresent(RestController.class)) {
                    //使用反射实例化对象
                    Object instance = cls.newInstance();
                    //默认类名首字母小写
                    beanName = firstLowerCase(cls.getSimpleName());
                    //写入ioc容器
                    ioc.put(beanName, instance);


                } else if (cls.isAnnotationPresent(Component.class)) {
                    Object instance = cls.newInstance();
                    Component component = (Component) cls.getAnnotation(Component.class);

                    String alisName = component.value();
                    if (null == alisName || alisName.trim().length() == 0) {
                        beanName = cls.getSimpleName();
                    } else {
                        beanName = alisName;
                    }
                    beanName = firstLowerCase(beanName);
                    ioc.put(beanName, instance);
                    //如果是接口，自动注入它的实现类
                    Class<?>[] interfaces = cls.getInterfaces();
                    for (Class<?> c :
                            interfaces) {
                        ioc.put(firstLowerCase(c.getSimpleName()), instance);
                    }
                } else {
                    continue;
                }
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
                e.printStackTrace();
            }
        }
    }

    String firstLowerCase(String str) {
        char[] chars = str.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }
}
