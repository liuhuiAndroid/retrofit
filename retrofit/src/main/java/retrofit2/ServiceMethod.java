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
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.HEAD;
import retrofit2.http.HTTP;
import retrofit2.http.Header;
import retrofit2.http.HeaderMap;
import retrofit2.http.Multipart;
import retrofit2.http.OPTIONS;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.PartMap;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;
import retrofit2.http.QueryName;
import retrofit2.http.Url;

/**
 * Adapts an invocation of an interface method into an HTTP call.
 * ServiceMethod对象包含了访问网络的所有基本信息
 * ServiceMethod几乎保存了一个网络请求所需要的数据
 * 发送网络请求时，OkHttpCall需要从ServiceMethod中获得一个Request对象
 * 解析数据时，还需要通过ServiceMethod使用Converter（数据转换器）转换成Java对象进行数据解析
 */
final class ServiceMethod<R, T> {
  // Upper and lower characters, digits, underscores, and hyphens, starting with a character.
  static final String PARAM = "[a-zA-Z][a-zA-Z0-9_-]*";
  static final Pattern PARAM_URL_REGEX = Pattern.compile("\\{(" + PARAM + ")\\}");
  static final Pattern PARAM_NAME_REGEX = Pattern.compile(PARAM);

  // 网络请求工厂
  final okhttp3.Call.Factory callFactory;

  // 网络请求适配器工厂
  // 具体创建是在new ServiceMethod.Builder(this, method).build()最后的build()中
  final CallAdapter<R, T> callAdapter;

  // 网络请求地址
  private final HttpUrl baseUrl;

  // Response内容转换器
  // 作用：负责把服务器返回的数据（JSON或者其他格式，由 ResponseBody 封装）转化为 T 类型的对象；
  private final Converter<ResponseBody, R> responseConverter;

  // 网络请求的Http方法
  private final String httpMethod;

  // 网络请求的相对地址
  private final String relativeUrl;

  // 网络请求的http请求头 键值对
  private final Headers headers;

  // 网络请求的http报文body的类型
  private final MediaType contentType;
  private final boolean hasBody;
  private final boolean isFormEncoded;
  private final boolean isMultipart;


  // 方法参数处理器
  // 作用：负责解析 API 定义时每个方法的参数，并在构造 HTTP 请求时设置参数；
  private final ParameterHandler<?>[] parameterHandlers;

  /**
   * 构造函数
   */
  ServiceMethod(Builder<R, T> builder) {
    this.callFactory = builder.retrofit.callFactory();
    this.callAdapter = builder.callAdapter;
    this.baseUrl = builder.retrofit.baseUrl();
    this.responseConverter = builder.responseConverter;
    this.httpMethod = builder.httpMethod;
    this.relativeUrl = builder.relativeUrl;
    this.headers = builder.headers;
    this.contentType = builder.contentType;
    this.hasBody = builder.hasBody;
    this.isFormEncoded = builder.isFormEncoded;
    this.isMultipart = builder.isMultipart;
    this.parameterHandlers = builder.parameterHandlers;
  }

  /** Builds an HTTP request from method arguments. */
  Request toRequest(@Nullable Object... args) throws IOException {
    RequestBuilder requestBuilder = new RequestBuilder(httpMethod, baseUrl, relativeUrl, headers,
        contentType, hasBody, isFormEncoded, isMultipart);

    @SuppressWarnings("unchecked") // It is an error to invoke a method with the wrong arg types.
    ParameterHandler<Object>[] handlers = (ParameterHandler<Object>[]) parameterHandlers;

    int argumentCount = args != null ? args.length : 0;
    if (argumentCount != handlers.length) {
      throw new IllegalArgumentException("Argument count (" + argumentCount
          + ") doesn't match expected count (" + handlers.length + ")");
    }

    for (int p = 0; p < argumentCount; p++) {
      handlers[p].apply(requestBuilder, args[p]);
    }

    return requestBuilder.build();
  }

  /** Builds a method return value from an HTTP response body. */
  R toResponse(ResponseBody body) throws IOException {
    return responseConverter.convert(body);
  }

  /**
   * Inspects the annotations on an interface method to construct a reusable service method. This
   * requires potentially-expensive reflection so it is best to build each service method only once
   * and reuse it. Builders cannot be reused.
   */
  static final class Builder<T, R> {
    final Retrofit retrofit;
    final Method method;
    final Annotation[] methodAnnotations;
    final Annotation[][] parameterAnnotationsArray;
    final Type[] parameterTypes;

    Type responseType;
    boolean gotField;
    boolean gotPart;
    boolean gotBody;
    boolean gotPath;
    boolean gotQuery;
    boolean gotUrl;
    String httpMethod;
    boolean hasBody;
    boolean isFormEncoded;
    boolean isMultipart;
    String relativeUrl;
    Headers headers;
    MediaType contentType;
    Set<String> relativeUrlParamNames;
    ParameterHandler<?>[] parameterHandlers;
    Converter<ResponseBody, T> responseConverter;
    CallAdapter<T, R> callAdapter;

    Builder(Retrofit retrofit, Method method) {
      this.retrofit = retrofit;
      this.method = method;

      // 获取网络请求接口方法里的注释
      this.methodAnnotations = method.getAnnotations();

      // 获取网络请求接口方法里的参数类型
      this.parameterTypes = method.getGenericParameterTypes();

      //获取网络请求接口方法里的注解内容
      this.parameterAnnotationsArray = method.getParameterAnnotations();
    }

    /**
     * 作用：控制ServiceMethod对象的生成流程
     * <-- 总结 -->
     * 1. 根据返回值类型和方法标注从Retrofit对象的的网络请求适配器工厂集合和内容转换器工厂集合中分别获取到该方法对应的网络请求适配器和Response内容转换器；
     * 2. 根据方法的标注对ServiceMethod的域进行赋值
     * 3. 最后为每个方法的参数的标注进行解析，获得一个ParameterHandler<?>对象
     * 该对象保存有一个Request内容转换器——根据参数的类型从Retrofit的内容转换器工厂集合中获取一个Request内容转换器或者一个String内容转换器。
     */
    public ServiceMethod build() {
      // -->关注点1
      // 根据网络请求接口方法的返回值和注解类型，从Retrofit对象中获取对应的网络请求适配器
      callAdapter = createCallAdapter();

      // 根据网络请求接口方法的返回值和注解类型，从Retrofit对象中获取该网络适配器返回的数据类型
      responseType = callAdapter.responseType();

      if (responseType == Response.class || responseType == okhttp3.Response.class) {
        throw methodError("'"
            + Utils.getRawType(responseType).getName()
            + "' is not a valid response body type. Did you mean ResponseBody?");
      }

      // -->关注点3
      // 根据网络请求接口方法的返回值和注解类型，从Retrofit对象中获取对应的数据转换器
      // 构造 HTTP 请求时，我们传递的参数都是String
      // Retrofit 类提供 converter把传递的参数都转化为 String
      // 其余类型的参数都利用 Converter.Factory 的stringConverter 进行转换
      // @Body 和 @Part 类型的参数利用Converter.Factory 提供的 requestBodyConverter 进行转换
      // 这三种 converter 都是通过“询问”工厂列表进行提供，而工厂列表我们可以在构造 Retrofit 对象时进行添加。
      responseConverter = createResponseConverter();

      // 解析网络请求接口中方法的注解
      // 主要是解析获取Http请求的方法
      // 注解包括：DELETE、GET、POST、HEAD、PATCH、PUT、OPTIONS、HTTP、retrofit2.http.Headers、Multipart、FormUrlEncoded
      // 处理主要是调用方法 parseHttpMethodAndPath(String httpMethod, String value, boolean hasBody) ServiceMethod中的httpMethod、hasBody、relativeUrl、relativeUrlParamNames域进行赋值
      for (Annotation annotation : methodAnnotations) {
        parseMethodAnnotation(annotation);
      }

      if (httpMethod == null) {
        throw methodError("HTTP method annotation is required (e.g., @GET, @POST, etc.).");
      }

      if (!hasBody) {
        if (isMultipart) {
          throw methodError(
              "Multipart can only be specified on HTTP methods with request body (e.g., @POST).");
        }
        if (isFormEncoded) {
          throw methodError("FormUrlEncoded can only be specified on HTTP methods with "
              + "request body (e.g., @POST).");
        }
      }

      // 获取当前方法的参数数量
      int parameterCount = parameterAnnotationsArray.length;
      parameterHandlers = new ParameterHandler<?>[parameterCount];
      for (int p = 0; p < parameterCount; p++) {
        Type parameterType = parameterTypes[p];
        if (Utils.hasUnresolvableType(parameterType)) {
          throw parameterError(p, "Parameter type must not include a type variable or wildcard: %s",
              parameterType);
        }

        // 为方法中的每个参数创建一个ParameterHandler<?>对象并解析每个参数使用的注解类型
        // 该对象的创建过程就是对方法参数中注解进行解析
        // 这里的注解包括：Body、PartMap、Part、FieldMap、Field、Header、QueryMap、Query、Path、Url
        Annotation[] parameterAnnotations = parameterAnnotationsArray[p];
        if (parameterAnnotations == null) {
          throw parameterError(p, "No Retrofit annotation found.");
        }

        parameterHandlers[p] = parseParameter(p, parameterType, parameterAnnotations);
      }

      if (relativeUrl == null && !gotUrl) {
        throw methodError("Missing either @%s URL or @Url parameter.", httpMethod);
      }
      if (!isFormEncoded && !isMultipart && !hasBody && gotBody) {
        throw methodError("Non-body HTTP method cannot contain @Body.");
      }
      if (isFormEncoded && !gotField) {
        throw methodError("Form-encoded method must contain at least one @Field.");
      }
      if (isMultipart && !gotPart) {
        throw methodError("Multipart method must contain at least one @Part.");
      }

      return new ServiceMethod<>(this);
    }

    private CallAdapter<T, R> createCallAdapter() {
      // 获取网络请求接口里方法的返回值类型
      Type returnType = method.getGenericReturnType();
      if (Utils.hasUnresolvableType(returnType)) {
        throw methodError(
            "Method return type must not include a type variable or wildcard: %s", returnType);
      }
      if (returnType == void.class) {
        throw methodError("Service methods cannot return void.");
      }
      // 获取网络请求接口接口里的注解
      // 此处使用的是@Get
      Annotation[] annotations = method.getAnnotations();
      try {
        //noinspection unchecked
        // -- >关注点2
        // 根据网络请求接口方法的返回值和注解类型，从Retrofit对象中获取对应的网络请求适配器
        // 下面会详细说明retrofit.callAdapter（）
        return (CallAdapter<T, R>) retrofit.callAdapter(returnType, annotations);
      } catch (RuntimeException e) { // Wide exception range because factories are user code.
        throw methodError(e, "Unable to create call adapter for %s", returnType);
      }
    }

    private void parseMethodAnnotation(Annotation annotation) {
      if (annotation instanceof DELETE) {
        parseHttpMethodAndPath("DELETE", ((DELETE) annotation).value(), false);
      } else if (annotation instanceof GET) {
        parseHttpMethodAndPath("GET", ((GET) annotation).value(), false);
      } else if (annotation instanceof HEAD) {
        parseHttpMethodAndPath("HEAD", ((HEAD) annotation).value(), false);
        if (!Void.class.equals(responseType)) {
          throw methodError("HEAD method must use Void as response type.");
        }
      } else if (annotation instanceof PATCH) {
        parseHttpMethodAndPath("PATCH", ((PATCH) annotation).value(), true);
      } else if (annotation instanceof POST) {
        parseHttpMethodAndPath("POST", ((POST) annotation).value(), true);
      } else if (annotation instanceof PUT) {
        parseHttpMethodAndPath("PUT", ((PUT) annotation).value(), true);
      } else if (annotation instanceof OPTIONS) {
        parseHttpMethodAndPath("OPTIONS", ((OPTIONS) annotation).value(), false);
      } else if (annotation instanceof HTTP) {
        HTTP http = (HTTP) annotation;
        parseHttpMethodAndPath(http.method(), http.path(), http.hasBody());
      } else if (annotation instanceof retrofit2.http.Headers) {
        String[] headersToParse = ((retrofit2.http.Headers) annotation).value();
        if (headersToParse.length == 0) {
          throw methodError("@Headers annotation is empty.");
        }
        headers = parseHeaders(headersToParse);
      } else if (annotation instanceof Multipart) {
        if (isFormEncoded) {
          throw methodError("Only one encoding annotation is allowed.");
        }
        isMultipart = true;
      } else if (annotation instanceof FormUrlEncoded) {
        if (isMultipart) {
          throw methodError("Only one encoding annotation is allowed.");
        }
        isFormEncoded = true;
      }
    }

    private void parseHttpMethodAndPath(String httpMethod, String value, boolean hasBody) {
      if (this.httpMethod != null) {
        throw methodError("Only one HTTP method is allowed. Found: %s and %s.",
            this.httpMethod, httpMethod);
      }
      this.httpMethod = httpMethod;
      this.hasBody = hasBody;

      if (value.isEmpty()) {
        return;
      }

      // Get the relative URL path and existing query string, if present.
      int question = value.indexOf('?');
      if (question != -1 && question < value.length() - 1) {
        // Ensure the query string does not have any named parameters.
        String queryParams = value.substring(question + 1);
        Matcher queryParamMatcher = PARAM_URL_REGEX.matcher(queryParams);
        if (queryParamMatcher.find()) {
          throw methodError("URL query string \"%s\" must not have replace block. "
              + "For dynamic query parameters use @Query.", queryParams);
        }
      }

      this.relativeUrl = value;
      this.relativeUrlParamNames = parsePathParameters(value);
    }

    private Headers parseHeaders(String[] headers) {
      Headers.Builder builder = new Headers.Builder();
      for (String header : headers) {
        int colon = header.indexOf(':');
        if (colon == -1 || colon == 0 || colon == header.length() - 1) {
          throw methodError(
              "@Headers value must be in the form \"Name: Value\". Found: \"%s\"", header);
        }
        String headerName = header.substring(0, colon);
        String headerValue = header.substring(colon + 1).trim();
        if ("Content-Type".equalsIgnoreCase(headerName)) {
          MediaType type = MediaType.parse(headerValue);
          if (type == null) {
            throw methodError("Malformed content type: %s", headerValue);
          }
          contentType = type;
        } else {
          builder.add(headerName, headerValue);
        }
      }
      return builder.build();
    }

    private ParameterHandler<?> parseParameter(
        int p, Type parameterType, Annotation[] annotations) {
      ParameterHandler<?> result = null;
      for (Annotation annotation : annotations) {
        ParameterHandler<?> annotationAction = parseParameterAnnotation(
            p, parameterType, annotations, annotation);

        if (annotationAction == null) {
          continue;
        }

        if (result != null) {
          throw parameterError(p, "Multiple Retrofit annotations found, only one allowed.");
        }

        result = annotationAction;
      }

      if (result == null) {
        throw parameterError(p, "No Retrofit annotation found.");
      }

      return result;
    }

    private ParameterHandler<?> parseParameterAnnotation(
        int p, Type type, Annotation[] annotations, Annotation annotation) {
      if (annotation instanceof Url) {
        if (gotUrl) {
          throw parameterError(p, "Multiple @Url method annotations found.");
        }
        if (gotPath) {
          throw parameterError(p, "@Path parameters may not be used with @Url.");
        }
        if (gotQuery) {
          throw parameterError(p, "A @Url parameter must not come after a @Query");
        }
        if (relativeUrl != null) {
          throw parameterError(p, "@Url cannot be used with @%s URL", httpMethod);
        }

        gotUrl = true;

        if (type == HttpUrl.class
            || type == String.class
            || type == URI.class
            || (type instanceof Class && "android.net.Uri".equals(((Class<?>) type).getName()))) {
          return new ParameterHandler.RelativeUrl();
        } else {
          throw parameterError(p,
              "@Url must be okhttp3.HttpUrl, String, java.net.URI, or android.net.Uri type.");
        }

      } else if (annotation instanceof Path) {
        if (gotQuery) {
          throw parameterError(p, "A @Path parameter must not come after a @Query.");
        }
        if (gotUrl) {
          throw parameterError(p, "@Path parameters may not be used with @Url.");
        }
        if (relativeUrl == null) {
          throw parameterError(p, "@Path can only be used with relative url on @%s", httpMethod);
        }
        gotPath = true;

        Path path = (Path) annotation;
        String name = path.value();
        validatePathName(p, name);

        Converter<?, String> converter = retrofit.stringConverter(type, annotations);
        return new ParameterHandler.Path<>(name, converter, path.encoded());

      } else if (annotation instanceof Query) {
        Query query = (Query) annotation;
        String name = query.value();
        boolean encoded = query.encoded();

        Class<?> rawParameterType = Utils.getRawType(type);
        gotQuery = true;
        if (Iterable.class.isAssignableFrom(rawParameterType)) {
          if (!(type instanceof ParameterizedType)) {
            throw parameterError(p, rawParameterType.getSimpleName()
                + " must include generic type (e.g., "
                + rawParameterType.getSimpleName()
                + "<String>)");
          }
          ParameterizedType parameterizedType = (ParameterizedType) type;
          Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
          Converter<?, String> converter =
              retrofit.stringConverter(iterableType, annotations);
          return new ParameterHandler.Query<>(name, converter, encoded).iterable();
        } else if (rawParameterType.isArray()) {
          Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
          Converter<?, String> converter =
              retrofit.stringConverter(arrayComponentType, annotations);
          return new ParameterHandler.Query<>(name, converter, encoded).array();
        } else {
          Converter<?, String> converter =
              retrofit.stringConverter(type, annotations);
          return new ParameterHandler.Query<>(name, converter, encoded);
        }

      } else if (annotation instanceof QueryName) {
        QueryName query = (QueryName) annotation;
        boolean encoded = query.encoded();

        Class<?> rawParameterType = Utils.getRawType(type);
        gotQuery = true;
        if (Iterable.class.isAssignableFrom(rawParameterType)) {
          if (!(type instanceof ParameterizedType)) {
            throw parameterError(p, rawParameterType.getSimpleName()
                + " must include generic type (e.g., "
                + rawParameterType.getSimpleName()
                + "<String>)");
          }
          ParameterizedType parameterizedType = (ParameterizedType) type;
          Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
          Converter<?, String> converter =
              retrofit.stringConverter(iterableType, annotations);
          return new ParameterHandler.QueryName<>(converter, encoded).iterable();
        } else if (rawParameterType.isArray()) {
          Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
          Converter<?, String> converter =
              retrofit.stringConverter(arrayComponentType, annotations);
          return new ParameterHandler.QueryName<>(converter, encoded).array();
        } else {
          Converter<?, String> converter =
              retrofit.stringConverter(type, annotations);
          return new ParameterHandler.QueryName<>(converter, encoded);
        }

      } else if (annotation instanceof QueryMap) {
        Class<?> rawParameterType = Utils.getRawType(type);
        if (!Map.class.isAssignableFrom(rawParameterType)) {
          throw parameterError(p, "@QueryMap parameter type must be Map.");
        }
        Type mapType = Utils.getSupertype(type, rawParameterType, Map.class);
        if (!(mapType instanceof ParameterizedType)) {
          throw parameterError(p, "Map must include generic types (e.g., Map<String, String>)");
        }
        ParameterizedType parameterizedType = (ParameterizedType) mapType;
        Type keyType = Utils.getParameterUpperBound(0, parameterizedType);
        if (String.class != keyType) {
          throw parameterError(p, "@QueryMap keys must be of type String: " + keyType);
        }
        Type valueType = Utils.getParameterUpperBound(1, parameterizedType);
        Converter<?, String> valueConverter =
            retrofit.stringConverter(valueType, annotations);

        return new ParameterHandler.QueryMap<>(valueConverter, ((QueryMap) annotation).encoded());

      } else if (annotation instanceof Header) {
        Header header = (Header) annotation;
        String name = header.value();

        Class<?> rawParameterType = Utils.getRawType(type);
        if (Iterable.class.isAssignableFrom(rawParameterType)) {
          if (!(type instanceof ParameterizedType)) {
            throw parameterError(p, rawParameterType.getSimpleName()
                + " must include generic type (e.g., "
                + rawParameterType.getSimpleName()
                + "<String>)");
          }
          ParameterizedType parameterizedType = (ParameterizedType) type;
          Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
          Converter<?, String> converter =
              retrofit.stringConverter(iterableType, annotations);
          return new ParameterHandler.Header<>(name, converter).iterable();
        } else if (rawParameterType.isArray()) {
          Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
          Converter<?, String> converter =
              retrofit.stringConverter(arrayComponentType, annotations);
          return new ParameterHandler.Header<>(name, converter).array();
        } else {
          Converter<?, String> converter =
              retrofit.stringConverter(type, annotations);
          return new ParameterHandler.Header<>(name, converter);
        }

      } else if (annotation instanceof HeaderMap) {
        Class<?> rawParameterType = Utils.getRawType(type);
        if (!Map.class.isAssignableFrom(rawParameterType)) {
          throw parameterError(p, "@HeaderMap parameter type must be Map.");
        }
        Type mapType = Utils.getSupertype(type, rawParameterType, Map.class);
        if (!(mapType instanceof ParameterizedType)) {
          throw parameterError(p, "Map must include generic types (e.g., Map<String, String>)");
        }
        ParameterizedType parameterizedType = (ParameterizedType) mapType;
        Type keyType = Utils.getParameterUpperBound(0, parameterizedType);
        if (String.class != keyType) {
          throw parameterError(p, "@HeaderMap keys must be of type String: " + keyType);
        }
        Type valueType = Utils.getParameterUpperBound(1, parameterizedType);
        Converter<?, String> valueConverter =
            retrofit.stringConverter(valueType, annotations);

        return new ParameterHandler.HeaderMap<>(valueConverter);

      } else if (annotation instanceof Field) {
        if (!isFormEncoded) {
          throw parameterError(p, "@Field parameters can only be used with form encoding.");
        }
        Field field = (Field) annotation;
        String name = field.value();
        boolean encoded = field.encoded();

        gotField = true;

        Class<?> rawParameterType = Utils.getRawType(type);
        if (Iterable.class.isAssignableFrom(rawParameterType)) {
          if (!(type instanceof ParameterizedType)) {
            throw parameterError(p, rawParameterType.getSimpleName()
                + " must include generic type (e.g., "
                + rawParameterType.getSimpleName()
                + "<String>)");
          }
          ParameterizedType parameterizedType = (ParameterizedType) type;
          Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
          Converter<?, String> converter =
              retrofit.stringConverter(iterableType, annotations);
          return new ParameterHandler.Field<>(name, converter, encoded).iterable();
        } else if (rawParameterType.isArray()) {
          Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
          Converter<?, String> converter =
              retrofit.stringConverter(arrayComponentType, annotations);
          return new ParameterHandler.Field<>(name, converter, encoded).array();
        } else {
          Converter<?, String> converter =
              retrofit.stringConverter(type, annotations);
          return new ParameterHandler.Field<>(name, converter, encoded);
        }

      } else if (annotation instanceof FieldMap) {
        if (!isFormEncoded) {
          throw parameterError(p, "@FieldMap parameters can only be used with form encoding.");
        }
        Class<?> rawParameterType = Utils.getRawType(type);
        if (!Map.class.isAssignableFrom(rawParameterType)) {
          throw parameterError(p, "@FieldMap parameter type must be Map.");
        }
        Type mapType = Utils.getSupertype(type, rawParameterType, Map.class);
        if (!(mapType instanceof ParameterizedType)) {
          throw parameterError(p,
              "Map must include generic types (e.g., Map<String, String>)");
        }
        ParameterizedType parameterizedType = (ParameterizedType) mapType;
        Type keyType = Utils.getParameterUpperBound(0, parameterizedType);
        if (String.class != keyType) {
          throw parameterError(p, "@FieldMap keys must be of type String: " + keyType);
        }
        Type valueType = Utils.getParameterUpperBound(1, parameterizedType);
        Converter<?, String> valueConverter =
            retrofit.stringConverter(valueType, annotations);

        gotField = true;
        return new ParameterHandler.FieldMap<>(valueConverter, ((FieldMap) annotation).encoded());

      } else if (annotation instanceof Part) {
        if (!isMultipart) {
          throw parameterError(p, "@Part parameters can only be used with multipart encoding.");
        }
        Part part = (Part) annotation;
        gotPart = true;

        String partName = part.value();
        Class<?> rawParameterType = Utils.getRawType(type);
        if (partName.isEmpty()) {
          if (Iterable.class.isAssignableFrom(rawParameterType)) {
            if (!(type instanceof ParameterizedType)) {
              throw parameterError(p, rawParameterType.getSimpleName()
                  + " must include generic type (e.g., "
                  + rawParameterType.getSimpleName()
                  + "<String>)");
            }
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
            if (!MultipartBody.Part.class.isAssignableFrom(Utils.getRawType(iterableType))) {
              throw parameterError(p,
                  "@Part annotation must supply a name or use MultipartBody.Part parameter type.");
            }
            return ParameterHandler.RawPart.INSTANCE.iterable();
          } else if (rawParameterType.isArray()) {
            Class<?> arrayComponentType = rawParameterType.getComponentType();
            if (!MultipartBody.Part.class.isAssignableFrom(arrayComponentType)) {
              throw parameterError(p,
                  "@Part annotation must supply a name or use MultipartBody.Part parameter type.");
            }
            return ParameterHandler.RawPart.INSTANCE.array();
          } else if (MultipartBody.Part.class.isAssignableFrom(rawParameterType)) {
            return ParameterHandler.RawPart.INSTANCE;
          } else {
            throw parameterError(p,
                "@Part annotation must supply a name or use MultipartBody.Part parameter type.");
          }
        } else {
          Headers headers =
              Headers.of("Content-Disposition", "form-data; name=\"" + partName + "\"",
                  "Content-Transfer-Encoding", part.encoding());

          if (Iterable.class.isAssignableFrom(rawParameterType)) {
            if (!(type instanceof ParameterizedType)) {
              throw parameterError(p, rawParameterType.getSimpleName()
                  + " must include generic type (e.g., "
                  + rawParameterType.getSimpleName()
                  + "<String>)");
            }
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
            if (MultipartBody.Part.class.isAssignableFrom(Utils.getRawType(iterableType))) {
              throw parameterError(p, "@Part parameters using the MultipartBody.Part must not "
                  + "include a part name in the annotation.");
            }
            Converter<?, RequestBody> converter =
                retrofit.requestBodyConverter(iterableType, annotations, methodAnnotations);
            return new ParameterHandler.Part<>(headers, converter).iterable();
          } else if (rawParameterType.isArray()) {
            Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
            if (MultipartBody.Part.class.isAssignableFrom(arrayComponentType)) {
              throw parameterError(p, "@Part parameters using the MultipartBody.Part must not "
                  + "include a part name in the annotation.");
            }
            Converter<?, RequestBody> converter =
                retrofit.requestBodyConverter(arrayComponentType, annotations, methodAnnotations);
            return new ParameterHandler.Part<>(headers, converter).array();
          } else if (MultipartBody.Part.class.isAssignableFrom(rawParameterType)) {
            throw parameterError(p, "@Part parameters using the MultipartBody.Part must not "
                + "include a part name in the annotation.");
          } else {
            Converter<?, RequestBody> converter =
                retrofit.requestBodyConverter(type, annotations, methodAnnotations);
            return new ParameterHandler.Part<>(headers, converter);
          }
        }

      } else if (annotation instanceof PartMap) {
        if (!isMultipart) {
          throw parameterError(p, "@PartMap parameters can only be used with multipart encoding.");
        }
        gotPart = true;
        Class<?> rawParameterType = Utils.getRawType(type);
        if (!Map.class.isAssignableFrom(rawParameterType)) {
          throw parameterError(p, "@PartMap parameter type must be Map.");
        }
        Type mapType = Utils.getSupertype(type, rawParameterType, Map.class);
        if (!(mapType instanceof ParameterizedType)) {
          throw parameterError(p, "Map must include generic types (e.g., Map<String, String>)");
        }
        ParameterizedType parameterizedType = (ParameterizedType) mapType;

        Type keyType = Utils.getParameterUpperBound(0, parameterizedType);
        if (String.class != keyType) {
          throw parameterError(p, "@PartMap keys must be of type String: " + keyType);
        }

        Type valueType = Utils.getParameterUpperBound(1, parameterizedType);
        if (MultipartBody.Part.class.isAssignableFrom(Utils.getRawType(valueType))) {
          throw parameterError(p, "@PartMap values cannot be MultipartBody.Part. "
              + "Use @Part List<Part> or a different value type instead.");
        }

        Converter<?, RequestBody> valueConverter =
            retrofit.requestBodyConverter(valueType, annotations, methodAnnotations);

        PartMap partMap = (PartMap) annotation;
        return new ParameterHandler.PartMap<>(valueConverter, partMap.encoding());

      } else if (annotation instanceof Body) {
        if (isFormEncoded || isMultipart) {
          throw parameterError(p,
              "@Body parameters cannot be used with form or multi-part encoding.");
        }
        if (gotBody) {
          throw parameterError(p, "Multiple @Body method annotations found.");
        }

        Converter<?, RequestBody> converter;
        try {
          converter = retrofit.requestBodyConverter(type, annotations, methodAnnotations);
        } catch (RuntimeException e) {
          // Wide exception range because factories are user code.
          throw parameterError(e, p, "Unable to create @Body converter for %s", type);
        }
        gotBody = true;
        return new ParameterHandler.Body<>(converter);
      }

      return null; // Not a Retrofit annotation.
    }

    private void validatePathName(int p, String name) {
      if (!PARAM_NAME_REGEX.matcher(name).matches()) {
        throw parameterError(p, "@Path parameter name must match %s. Found: %s",
            PARAM_URL_REGEX.pattern(), name);
      }
      // Verify URL replacement name is actually present in the URL path.
      if (!relativeUrlParamNames.contains(name)) {
        throw parameterError(p, "URL \"%s\" does not contain \"{%s}\".", relativeUrl, name);
      }
    }

    private Converter<ResponseBody, T> createResponseConverter() {
      Annotation[] annotations = method.getAnnotations();
      try {
        // -->关注点4
        // responseConverter 还是由 Retrofit 类提供
        return retrofit.responseBodyConverter(responseType, annotations);
      } catch (RuntimeException e) { // Wide exception range because factories are user code.
        throw methodError(e, "Unable to create converter for %s", responseType);
      }
    }

    private RuntimeException methodError(String message, Object... args) {
      return methodError(null, message, args);
    }

    private RuntimeException methodError(Throwable cause, String message, Object... args) {
      message = String.format(message, args);
      return new IllegalArgumentException(message
          + "\n    for method "
          + method.getDeclaringClass().getSimpleName()
          + "."
          + method.getName(), cause);
    }

    private RuntimeException parameterError(
        Throwable cause, int p, String message, Object... args) {
      return methodError(cause, message + " (parameter #" + (p + 1) + ")", args);
    }

    private RuntimeException parameterError(int p, String message, Object... args) {
      return methodError(message + " (parameter #" + (p + 1) + ")", args);
    }
  }

  /**
   * Gets the set of unique path parameters used in the given URI. If a parameter is used twice
   * in the URI, it will only show up once in the set.
   */
  static Set<String> parsePathParameters(String path) {
    Matcher m = PARAM_URL_REGEX.matcher(path);
    Set<String> patterns = new LinkedHashSet<>();
    while (m.find()) {
      patterns.add(m.group(1));
    }
    return patterns;
  }

  static Class<?> boxIfPrimitive(Class<?> type) {
    if (boolean.class == type) return Boolean.class;
    if (byte.class == type) return Byte.class;
    if (char.class == type) return Character.class;
    if (double.class == type) return Double.class;
    if (float.class == type) return Float.class;
    if (int.class == type) return Integer.class;
    if (long.class == type) return Long.class;
    if (short.class == type) return Short.class;
    return type;
  }
}
