package r.data;

import r.*;
import r.nodes.*;
import r.nodes.truffle.*;

public final class RNull implements RAny, RAttributes {

    private static RNull instance = new RNull();

    private RNull() {
    }

    @Override
    public RAttributes getAttributes() {
        return this;
    }

    @Override
    public String pretty() {
        return "NULL";
    }

    @Override
    public Object get(int i) {
        return this;
    }

    @Override
    public RArray set(int i, Object val) {
        return this;
    }

    @Override
    public RArray subset(RAny keys) {
        return this;
    }

    @Override
    public RArray subset(RInt index) {
        return this;
    }

    @Override
    public RArray subset(RString names) {
        return this;
    }

    @Override
    public RInt asInt() {
        Utils.nyi();
        return null;
    }

    @Override
    public RLogical asLogical() {
        Utils.nyi();
        return null;
    }

    @Override
    public RArray materialize() {
        return this;
    }

    public static RNull getNull() {
        return instance;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public <T extends RNode> T callNodeFactory(OperationFactory<T> factory) {
        return factory.fromNull();
    }
}