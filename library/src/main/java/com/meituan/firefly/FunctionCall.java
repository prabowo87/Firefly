package com.meituan.firefly;

import com.meituan.firefly.annotations.Field;
import com.meituan.firefly.annotations.Func;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by ponyets on 15/6/17.
 */
public class FunctionCall {
    private final List<FieldSpec> requestTypeList = new ArrayList<>();
    private final HashMap<Short, FieldSpec> responseExceptionTypeMap = new HashMap<>();
    private FieldSpec responseSuccessType;
    private final String methodName;
    private final boolean oneway;
    private final TStruct argsStruct;

    public FunctionCall(Method method, Thrift thrift) {
        methodName = method.getName();
        Func func = method.getAnnotation(Func.class);
        if (func == null) {
            throw new IllegalArgumentException("method " + methodName + " should be annotated with @Func");
        }
        oneway = func.oneway();
        parseRequest(method, thrift);
        parseResponse(method, func, thrift);
        argsStruct = new TStruct(methodName + "_args");
    }

    void parseRequest(Method method, Thrift thrift) {
        Parameter[] parameters = method.getParameters();
        Type[] parameterTypes = method.getGenericParameterTypes();
        for (int i = 0, n = parameters.length; i < n; i++) {
            Parameter parameter = parameters[i];
            Field paramField = parameter.getAnnotation(Field.class);
            if (paramField == null) {
                throw new IllegalArgumentException("parameter " + parameter.getName() + " of method " + methodName + " should be annotated with @Field");
            }
            TypeAdapter typeAdapter = thrift.getAdapter(parameterTypes[i]);
            requestTypeList.add(new FieldSpec(paramField.id(), paramField.required(), parameter.getName(), typeAdapter));
        }
    }

    void parseResponse(Method method, Func func, Thrift thrift) {
        TypeAdapter returnTypeAdapter = thrift.getAdapter(method.getGenericReturnType());
        responseSuccessType = new FieldSpec((short) 0, false, "success", returnTypeAdapter); //success

        Field[] exceptionFields = func.value();
        Class<?>[] exceptions = method.getExceptionTypes();
        if (exceptionFields != null) {
            for (int i = 0, n = exceptionFields.length; i < n; i++) {
                Field exceptionField = exceptionFields[i];
                TypeAdapter exceptionTypeAdapter = thrift.getAdapter(exceptions[i]);
                responseExceptionTypeMap.put(exceptionField.id(), new FieldSpec(exceptionField.id(), false, exceptions[i].getSimpleName(), exceptionTypeAdapter));
            }
        }
    }

    Object apply(Object[] args, TProtocol protocol, int seqid) throws Exception {
        send(args, protocol, seqid);
        if (!oneway) {
            return recv(protocol, seqid);
        }
        return null;
    }

    void send(Object[] args, TProtocol protocol, int seqid) throws TException {
        protocol.writeMessageBegin(new TMessage(methodName, TMessageType.CALL, seqid));
        protocol.writeStructBegin(argsStruct);
        for (int i = 0, n = args.length; i < n; i++) {
            FieldSpec fieldSpec = requestTypeList.get(i);
            Object value = args[i];
            if (value == null) {
                if (fieldSpec.required) {
                    throw new TProtocolException("Required field '" + fieldSpec.name + "' was not present! Struct: " + argsStruct.name);
                }
            } else {
                protocol.writeFieldBegin(fieldSpec.tField);
                fieldSpec.typeAdapter.write(value, protocol);
                protocol.writeFieldEnd();
            }
        }
        protocol.writeFieldStop();
        protocol.writeStructEnd();
        protocol.writeMessageEnd();
        protocol.getTransport().flush();
    }

    Object recv(TProtocol protocol, int seqid) throws Exception {
        TMessage msg = protocol.readMessageBegin();
        if (msg.type == TMessageType.EXCEPTION) {
            TApplicationException applicationException = TApplicationException.read(protocol);
            protocol.readMessageEnd();
            throw applicationException;
        }
        if (msg.seqid != seqid) {
            throw new TApplicationException(TApplicationException.BAD_SEQUENCE_ID, methodName + " failed: out of sequence response");
        }
        protocol.readStructBegin();
        Object success = null;
        Exception exception = null;
        while (true) {
            TField tField = protocol.readFieldBegin();
            if (tField.type == TType.STOP) {
                break;
            }
            FieldSpec fieldSpec = null;
            if (tField.id == 0) {
                fieldSpec = responseSuccessType;
            } else {
                fieldSpec = responseExceptionTypeMap.get(tField.id);
            }
            if (fieldSpec == null || fieldSpec.typeAdapter.getTType() != tField.type) {
                TProtocolUtil.skip(protocol, tField.type);
            } else {
                Object value = fieldSpec.typeAdapter.read(protocol);
                if (tField.id == 0) {
                    success = value;
                } else {
                    exception = (Exception) value;
                }
            }
            protocol.readFieldEnd();
        }
        protocol.readStructEnd();
        protocol.readMessageEnd();
        if (exception != null) {
            throw exception;
        }
        if (success != null) {
            return success;
        }
        throw new TApplicationException(org.apache.thrift.TApplicationException.MISSING_RESULT, methodName + " failed: unknown result");
    }

    static class FieldSpec {
        final short id;
        final boolean required;
        final String name;
        final TypeAdapter typeAdapter;
        final TField tField;

        public FieldSpec(short id, boolean required, String name, TypeAdapter typeAdapter) {
            this.id = id;
            this.required = required;
            this.name = name;
            this.typeAdapter = typeAdapter;
            this.tField = new TField(name, typeAdapter.getTType(), id);
        }
    }
}