package com.vay.camerasnap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflection {
    static Method method(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(cls.getName() + "." + name);
    }

    static Object field(Object instance, String name) throws ReflectiveOperationException {
        for (Class<?> cls = instance.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
