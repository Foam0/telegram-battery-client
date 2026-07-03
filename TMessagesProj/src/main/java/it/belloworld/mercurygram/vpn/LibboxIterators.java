package it.belloworld.mercurygram.vpn;

import java.util.ArrayList;
import java.util.Collection;

import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.StringIterator;

public final class LibboxIterators {
    private LibboxIterators() {
    }

    public static final class StringListIterator implements StringIterator {
        private final ArrayList<String> items;

        public StringListIterator(Collection<String> values) {
            items = new ArrayList<>(values);
        }

        @Override
        public boolean hasNext() {
            return !items.isEmpty();
        }

        @Override
        public int len() {
            return items.size();
        }

        @Override
        public String next() {
            if (items.isEmpty()) {
                return "";
            }
            return items.remove(0);
        }
    }

    public static final class BoxNetworkInterfaceIterator implements NetworkInterfaceIterator {
        private final ArrayList<NetworkInterface> items;

        public BoxNetworkInterfaceIterator(Collection<NetworkInterface> values) {
            items = new ArrayList<>(values);
        }

        @Override
        public boolean hasNext() {
            return !items.isEmpty();
        }

        @Override
        public NetworkInterface next() {
            if (items.isEmpty()) {
                return null;
            }
            return items.remove(0);
        }
    }
}
