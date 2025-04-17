package org.h2.mvstore;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class AdaptiveLRUCache<K, V> extends LinkedHashMap<K, V> {
    private int maxSize;

    public AdaptiveLRUCache(int initialSize) {
        super(initialSize, 0.75f, true);
        this.maxSize = initialSize;
    }

    public void setMaxSize(int newSize) {
        this.maxSize = newSize;
        trimToSize();
    }

    public int getMaxSize() {
        return this.maxSize;
    }

    public void trimToSize() {
        while (size() > maxSize) {
            Iterator<Map.Entry<K, V>> it = entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }
}

