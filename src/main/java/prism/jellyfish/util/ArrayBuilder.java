package prism.jellyfish.util;
import java.util.ArrayList;

import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;


public class ArrayBuilder<T extends Pointer> {
    ArrayList<T> elements;
    public ArrayBuilder() {
        this.elements = new ArrayList<T>();
    }

    public void add(T e) {
        elements.add(e);
    }

    public int length() {
        return elements.size();
    }

    public PointerPointer build() {
        Pointer[] pointerArray = elements.toArray(new Pointer[length()]);
        PointerPointer pp = new PointerPointer(pointerArray);
        return pp;
    }


}
