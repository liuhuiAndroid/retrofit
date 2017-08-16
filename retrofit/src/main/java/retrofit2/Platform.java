/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit2;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

class Platform {
  private static final Platform PLATFORM = findPlatform();

  static Platform get() {
    // 返回静态变量PLATFORM，即findPlatform()
    return PLATFORM;
  }

  private static Platform findPlatform() {
    try {
      // Class.forName(xxx.xx.xx)的作用：要求JVM查找并加载指定的类（即JVM会执行该类的静态代码段）
      Class.forName("android.os.Build");
      if (Build.VERSION.SDK_INT != 0) {
        // 返回一个Platform对象（指定了Android平台）
        return new Android();
      }
    } catch (ClassNotFoundException ignored) {
    }
    try {
      // 支持Java平台
      Class.forName("java.util.Optional");
      return new Java8();
    } catch (ClassNotFoundException ignored) {
    }
    return new Platform();
  }

  @Nullable Executor defaultCallbackExecutor() {
    return null;
  }

  CallAdapter.Factory defaultCallAdapterFactory(@Nullable Executor callbackExecutor) {
    if (callbackExecutor != null) {
      return new ExecutorCallAdapterFactory(callbackExecutor);
    }
    return DefaultCallAdapterFactory.INSTANCE;
  }

  boolean isDefaultMethod(Method method) {
    return false;
  }

  @Nullable Object invokeDefaultMethod(Method method, Class<?> declaringClass, Object object,
      @Nullable Object... args) throws Throwable {
    throw new UnsupportedOperationException();
  }

  @IgnoreJRERequirement // Only classloaded and used on Java 8.
  static class Java8 extends Platform {
    @Override boolean isDefaultMethod(Method method) {
      return method.isDefault();
    }

    @Override Object invokeDefaultMethod(Method method, Class<?> declaringClass, Object object,
        @Nullable Object... args) throws Throwable {
      // Because the service interface might not be public, we need to use a MethodHandle lookup
      // that ignores the visibility of the declaringClass.
      Constructor<Lookup> constructor = Lookup.class.getDeclaredConstructor(Class.class, int.class);
      constructor.setAccessible(true);
      return constructor.newInstance(declaringClass, -1 /* trusted */)
          .unreflectSpecial(method, declaringClass)
          .bindTo(object)
          .invokeWithArguments(args);
    }
  }

  /**
   * 用于接收服务器返回数据后进行线程切换在主线程显示结果
   * 切换线程的流程：
   * 1. 回调ExecutorCallAdapterFactory生成了一个ExecutorCallbackCall对象
   * 2. 通过调用ExecutorCallbackCall.enqueue(CallBack)从而调用MainThreadExecutor的execute()通过handler切换到主线程
   */
  static class Android extends Platform {
    @Override public Executor defaultCallbackExecutor() {
      // 返回一个默认的回调方法执行器
      // 该执行器作用：切换线程（子->>主线程），并在主线程（UI线程）中执行回调方法
      return new MainThreadExecutor();
    }

    @Override CallAdapter.Factory defaultCallAdapterFactory(@Nullable Executor callbackExecutor) {
      if (callbackExecutor == null) throw new AssertionError();
      // 创建默认的网络请求适配器工厂
      // 该默认工厂生产的 adapter 会使得Call在异步调用时在指定的 Executor 上执行回调
      // 在Retrofit中提供了四种CallAdapterFactory： ExecutorCallAdapterFactory（默认）、GuavaCallAdapterFactory、Java8CallAdapterFactory、RxJava2CallAdapterFactory
      // 采用了策略模式
      return new ExecutorCallAdapterFactory(callbackExecutor);
    }

    static class MainThreadExecutor implements Executor {
      // 获取与Android 主线程绑定的Handler
      private final Handler handler = new Handler(Looper.getMainLooper());

      @Override public void execute(Runnable r) {
        // 在UI线程进行对网络请求返回数据处理等操作。
        handler.post(r);
      }
    }
  }
}
