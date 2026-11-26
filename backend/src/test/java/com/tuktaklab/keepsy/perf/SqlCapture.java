package com.tuktaklab.keepsy.perf;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * 측정 중 실행된 SQL과 바인딩 값을 모은다 (Keepsy #350). 새 의존성 없이 {@link DataSource}를 JDK 프록시로 감싼다. 측정 컨텍스트({@link
 * PerfConfig})에서만 쓰고 운영 코드는 바꾸지 않는다.
 *
 * <p>{@link #start()}와 {@link #stop()} 사이에 실행된 문장만 기록한다. 측정은 한 스레드에서 차례로 하고 스케줄러는 멈춰 둔다.
 */
final class SqlCapture {

    /** 실행된 문장 하나. {@code params}는 1부터 시작하는 바인딩 순서 → 값 */
    record Executed(String sql, Map<Integer, Object> params) {}

    private volatile boolean recording;
    private final List<Executed> executed = new ArrayList<>();

    void start() {
        synchronized (executed) {
            executed.clear();
        }
        recording = true;
    }

    List<Executed> stop() {
        recording = false;
        synchronized (executed) {
            return List.copyOf(executed);
        }
    }

    DataSource wrap(DataSource target) {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            Object result = invoke(target, method, args);
                            return result instanceof Connection c ? connection(c) : result;
                        });
    }

    private Connection connection(Connection target) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(target, method, args);
                            if (result instanceof PreparedStatement ps
                                    && method.getName().equals("prepareStatement")) {
                                return prepared(ps, (String) args[0]);
                            }
                            if (result instanceof Statement s
                                    && !(result instanceof PreparedStatement)) {
                                return plain(s);
                            }
                            return result;
                        });
    }

    private PreparedStatement prepared(PreparedStatement target, String sql) {
        Map<Integer, Object> params = new TreeMap<>();
        InvocationHandler handler =
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set")
                            && args != null
                            && args.length >= 2
                            && args[0] instanceof Integer index) {
                        params.put(index, name.equals("setNull") ? null : args[1]);
                    } else if (name.equals("clearParameters")) {
                        params.clear();
                    } else if (name.startsWith("execute") && (args == null || args.length == 0)) {
                        record(sql, new TreeMap<>(params));
                    }
                    return invoke(target, method, args);
                };
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class<?>[] {PreparedStatement.class},
                        handler);
    }

    private Statement plain(Statement target) {
        return (Statement)
                Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class<?>[] {Statement.class},
                        (proxy, method, args) -> {
                            if (method.getName().startsWith("execute")
                                    && args != null
                                    && args.length >= 1
                                    && args[0] instanceof String sql) {
                                record(sql, Map.of());
                            }
                            return invoke(target, method, args);
                        });
    }

    private void record(String sql, Map<Integer, Object> params) {
        if (recording) {
            synchronized (executed) {
                executed.add(new Executed(sql, params));
            }
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
