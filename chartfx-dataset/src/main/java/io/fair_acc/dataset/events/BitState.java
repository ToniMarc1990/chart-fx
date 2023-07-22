package io.fair_acc.dataset.events;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * @author ennerf
 */
public class BitState implements StateListener {

    public Runnable onAction(IntSupplier bit0, IntSupplier... more) {
        return onAction(mask(bit0, more));
    }

    public Runnable onAction(int bits) {
        return () -> setDirty(bits);
    }

    public OnChangeSetter onPropChange(IntSupplier bit0, IntSupplier... more) {
        return onPropChange(mask(bit0, more));
    }

    public OnChangeSetter onPropChange(int bits) {
        return (obs, o, v) -> setDirty(bits);
    }

    public OnInvalidateSetter onPropInvalidate(IntSupplier bit0, IntSupplier... more) {
        return onPropInvalidate(mask(bit0, more));
    }

    public OnInvalidateSetter onPropInvalidate(int bits) {
        return obs -> setDirty(bits);
    }

    public static int mask(IntSupplier[] bits){
        int mask = 0;
        for (IntSupplier bit : bits) {
            mask |= bit.getAsInt();
        }
        return mask;
    }

    public static int mask(IntSupplier bit0, IntSupplier... more) {
        int mask = bit0.getAsInt();
        for (var bit : more) {
            mask |= bit.getAsInt();
        }
        return mask;
    }

    public void setDirty(int bits) {
        final int filtered = bits & filter;
        final int delta = (state ^ filtered) & filtered;
        if (delta != 0) {
            state |= filtered;
            notifyListeners(changeListeners, delta);
        }
        notifyListeners(invalidateListeners, bits);
    }

    @Override
    public void accept(BitState source, int bits) {
        setDirty(bits);
    }

    public void setDirty(IntSupplier bit0, IntSupplier... bits) {
        setDirty(mask(bit0, bits));
    }

    public boolean isDirty() {
        return state != 0;
    }

    public boolean isDirty(int mask) {
        return (state & mask) != 0;
    }

    public boolean isDirty(IntSupplier bit0) {
        return isDirty(bit0.getAsInt());
    }

    public boolean isDirty(IntSupplier bit0, IntSupplier bit1) {
        return isDirty(bit0.getAsInt() | bit1.getAsInt());
    }

    public boolean isDirty(IntSupplier bit0, IntSupplier bit1, IntSupplier... bits) {
        return isDirty(bit0.getAsInt() | bit1.getAsInt() | mask(bits));
    }

    public boolean isClean() {
        return state == 0;
    }

    public boolean isClean(int mask) {
        return (state & mask) == 0;
    }

    public boolean isClean(IntSupplier bit0) {
        return isClean(bit0.getAsInt());
    }

    public boolean isClean(IntSupplier bit0, IntSupplier bit1) {
        return isClean(bit0.getAsInt() | bit1.getAsInt());
    }

    public boolean isClean(IntSupplier bit0, IntSupplier bit1, IntSupplier... bits) {
        return isClean(bit0.getAsInt() | bit1.getAsInt() | mask(bits));
    }

    public int getBits() {
        return state;
    }

    public void getBits(StateListener action) {
        action.accept(this, state);
    }

    public void clear() {
        state = 0;
    }

    public int clear(final int mask) {
        state &= ~mask;
        return state;
    }

    public int clear(IntSupplier bit0) {
        return clear(bit0.getAsInt());
    }

    public int clear(IntSupplier bit0, IntSupplier bit1) {
        return clear(bit0.getAsInt() | bit1.getAsInt());
    }

    public int clear(IntSupplier bit0, IntSupplier bit1, IntSupplier... bits) {
        return clear(bit0.getAsInt() | bit1.getAsInt() | mask(bits));
    }

    public BitState addChangeListener(IntSupplier bit, StateListener listener) {
        return addChangeListener(bit.getAsInt(), listener);
    }

    public BitState addChangeListener(int filter, StateListener listener) {
        return addChangeListener(new FilteredListener(filter, listener));
    }

    public BitState addInvalidateListener(IntSupplier bit, StateListener listener) {
        return addInvalidateListener(bit.getAsInt(), listener);
    }

    public BitState addInvalidateListener(int filter, StateListener listener) {
        return addInvalidateListener(new FilteredListener(filter, listener));
    }

    public BitState addChangeListener(StateListener listener) {
        if(changeListeners == null) {
            changeListeners = new ArrayList<>();
        }
        changeListeners.add(listener);
        return this;
    }

    public BitState addInvalidateListener(StateListener listener) {
        if(invalidateListeners == null) {
            invalidateListeners = new ArrayList<>();
        }
        invalidateListeners.add(listener);
        return this;
    }

    public boolean removeChangeListener(StateListener listener) {
        return removeListener(changeListeners, listener);
    }

    public boolean removeInvalidateListener(StateListener listener) {
        return removeListener(invalidateListeners, listener);
    }

    private static boolean removeListener(List<StateListener> list,  StateListener listener) {
        if (list == null) {
            return false;
        }
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (isEqual(list.get(i), listener)) {
                list.remove(i);
                removed++;
            }
        }
        if (removed > 1) {
            throw new IllegalStateException("Can't remove targets that have been added more than once");
        }
        return removed == 1;
    }

    private void notifyListeners(List<StateListener> list, int delta) {
        if (list != null) {
            for (StateListener onChangeListener : list) {
                onChangeListener.accept(this, delta);
            }
        }
    }

    private static boolean isEqual(StateListener internal, StateListener listener) {
        if (internal == listener) {
            return true;
        }
        if (internal instanceof FilteredListener) {
            return ((FilteredListener) internal).listener == listener;
        }
        return false;
    }

    private static class FilteredListener implements StateListener {

        private FilteredListener(int filter, StateListener listener) {
            this.filter = filter;
            this.listener = listener;
        }

        @Override
        public void accept(BitState source, int bits) {
            final int filteredBits = filter & bits;
            if(filteredBits != 0){
                listener.accept(source, filteredBits);
            }
        }

        final int filter;
        final StateListener listener;

    }

    public Object getSource() {
        return source;
    }

    public static BitState initClean(Object source) {
        return initClean(source, NO_FILTER);
    }

    public static BitState initDirty(Object source) {
        return initDirty(source, NO_FILTER);
    }

    public static BitState initClean(Object source, int filter) {
        return new BitState(source, filter, 0);
    }

    public static BitState initDirty(Object source, int filter) {
        return new BitState(source, filter, filter);
    }

    protected BitState(Object source, int filter, int initial) {
        this.source = source;
        this.filter = filter;
        this.state = initial;
    }

    @Override
    public String toString() {
        return "bits(" + String.valueOf(source) + ")";
    }

    int state;
    final Object source;
    final int filter;
    public static final int NO_FILTER = ~0;

    List<StateListener> changeListeners;
    List<StateListener> invalidateListeners = null;

}