/*
 * Copyright (C) 2015 Square, Inc.
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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.concurrent.Executor;
import okhttp3.Request;

import static retrofit2.Utils.checkNotNull;

/**
 * 默认的CallAdapter.Factory
 */
final class ExecutorCallAdapterFactory extends CallAdapter.Factory {
  final Executor callbackExecutor;

  ExecutorCallAdapterFactory(Executor callbackExecutor) {
    this.callbackExecutor = callbackExecutor;
  }

  @Override
  public CallAdapter<?, ?> get(Type returnType, Annotation[] annotations, Retrofit retrofit) {
    if (getRawType(returnType) != Call.class) {
      return null;
    }
    final Type responseType = Utils.getCallResponseType(returnType);
    return new CallAdapter<Object, Call<?>>() {
      @Override public Type responseType() {
        return responseType;
      }

      /**
       * adapt返回的是ExecutorCallbackCall对象
       */
      @Override public Call<Object> adapt(Call<Object> call) {
        return new ExecutorCallbackCall<>(callbackExecutor, call);
      }
    };
  }

  /**
   * ExecutorCallbackCall仅仅是对Call对象进行封装，类似装饰者模式，只不过将其执行时的回调通过callbackExecutor进行回调到UI线程中去了。
   * @param <T>
   */
  static final class ExecutorCallbackCall<T> implements Call<T> {
    final Executor callbackExecutor;
    final Call<T> delegate;

    // 装饰模式:ExecutorCallbackCall = 装饰者，而里面真正去执行网络请求的还是OkHttpCall
    // 使用装饰模式的原因：希望在OkHttpCall发送请求时做一些额外操作。这里的额外操作是线程转换，即将子线程切换到主线程
    // ExecutorCallbackCall就是用于线程回调
    ExecutorCallbackCall(Executor callbackExecutor, Call<T> delegate) {

      // 传入上面定义的回调方法执行器
      // 用于进行线程切换
      this.callbackExecutor = callbackExecutor;

      // 把上面创建并配置好参数的OkhttpCall对象交给静态代理delegate
      // 静态代理和动态代理都属于代理模式
      // 静态代理作用：代理执行被代理者的方法，且可在要执行的方法前后加入自己的动作，进行对系统功能的拓展
      this.delegate = delegate;
    }

    @Override public void enqueue(final Callback<T> callback) {
      checkNotNull(callback, "callback == null");

      // 这个delegate实际上就是OkHttpCall对象
      delegate.enqueue(new Callback<T>() {
        @Override public void onResponse(Call<T> call, final Response<T> response) {
          callbackExecutor.execute(new Runnable() {
            @Override public void run() {
              if (delegate.isCanceled()) {
                // Emulate OkHttp's behavior of throwing/delivering an IOException on cancellation.
                callback.onFailure(ExecutorCallbackCall.this, new IOException("Canceled"));
              } else {
                callback.onResponse(ExecutorCallbackCall.this, response);
              }
            }
          });
        }

        @Override public void onFailure(Call<T> call, final Throwable t) {
          callbackExecutor.execute(new Runnable() {
            @Override public void run() {
              callback.onFailure(ExecutorCallbackCall.this, t);
            }
          });
        }
      });
    }

    @Override public boolean isExecuted() {
      return delegate.isExecuted();
    }

    @Override public Response<T> execute() throws IOException {
      return delegate.execute();
    }

    @Override public void cancel() {
      delegate.cancel();
    }

    @Override public boolean isCanceled() {
      return delegate.isCanceled();
    }

    @SuppressWarnings("CloneDoesntCallSuperClone") // Performing deep clone.
    @Override public Call<T> clone() {
      return new ExecutorCallbackCall<>(callbackExecutor, delegate.clone());
    }

    @Override public Request request() {
      return delegate.request();
    }
  }
}
