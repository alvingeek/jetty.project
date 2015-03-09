//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.SpinLock;

/**
 * An ChannelEndpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements ManagedSelector.SelectableEndPoint
{
    public static final Logger LOG = Log.getLogger(SelectChannelEndPoint.class);

    private final SpinLock _lock = new SpinLock();
    private boolean _updatePending;

    /**
     * true if {@link ManagedSelector#destroyEndPoint(EndPoint)} has not been called
     */
    private final AtomicBoolean _open = new AtomicBoolean();
    private final ManagedSelector _selector;
    private final SelectionKey _key;
    /**
     * The desired value for {@link SelectionKey#interestOps()}
     */
    private int _interestOps;

    private final Runnable _runUpdateKey = new Runnable() { public void run() { updateKey(); } };
    private final Runnable _runFillable = new Runnable() { public void run() { getFillInterest().fillable(); } };
    private final Runnable _runCompleteWrite = new Runnable() { public void run() { getWriteFlusher().completeWrite(); } };
    private final Runnable _runFillableCompleteWrite = new Runnable() { public void run() {  getFillInterest().fillable(); getWriteFlusher().completeWrite(); } };

    public SelectChannelEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler, long idleTimeout)
    {
        super(scheduler, channel);
        _selector = selector;
        _key = key;
        setIdleTimeout(idleTimeout);
    }

    @Override
    protected void needsFillInterest()
    {
        changeInterests(SelectionKey.OP_READ);
    }

    @Override
    protected void onIncompleteFlush()
    {
        changeInterests(SelectionKey.OP_WRITE);
    }

    @Override
    public Runnable onSelected()
    {
        /**
         * This method may run concurrently with {@link #changeInterests(int)}.
         */

        int readyOps;
        int oldInterestOps;
        int newInterestOps;
        try (SpinLock.Lock lock = _lock.lock())
        {
            _updatePending = true;

            // Remove the readyOps, that here can only be OP_READ or OP_WRITE (or both).
            readyOps = _key.readyOps();
            oldInterestOps = _interestOps;
            newInterestOps = oldInterestOps & ~readyOps;
            _interestOps = newInterestOps;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("onSelected {}->{} for {}", oldInterestOps, newInterestOps, this);

        boolean readable = (readyOps & SelectionKey.OP_READ) != 0;
        boolean writable = (readyOps & SelectionKey.OP_WRITE) != 0;
        return readable ? (writable ? _runFillableCompleteWrite : _runFillable)
                        : (writable ? _runCompleteWrite : null);
    }

    @Override
    public void updateKey()
    {
        /**
         * This method may run concurrently with {@link #changeInterests(int)}.
         */

        try
        {
            int oldInterestOps;
            int newInterestOps;
            try (SpinLock.Lock lock = _lock.lock())
            {
                _updatePending = false;
                oldInterestOps = _key.interestOps();
                newInterestOps = _interestOps;
                if (oldInterestOps != newInterestOps)
                    _key.interestOps(newInterestOps);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Key interests updated {} -> {} on {}", oldInterestOps, newInterestOps, this);
        }
        catch (CancelledKeyException x)
        {
            LOG.debug("Ignoring key update for concurrently closed channel {}", this);
            close();
        }
        catch (Throwable x)
        {
            LOG.warn("Ignoring key update for " + this, x);
            close();
        }
    }

    private void changeInterests(int operation)
    {
        /**
         * This method may run concurrently with
         * {@link #updateKey()} and {@link #onSelected()}.
         */

        int oldInterestOps;
        int newInterestOps;
        boolean pending;
        try (SpinLock.Lock lock = _lock.lock())
        {
            pending = _updatePending;
            oldInterestOps = _interestOps;
            newInterestOps = oldInterestOps | operation;
            if (newInterestOps != oldInterestOps)
                _interestOps = newInterestOps;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("changeInterests p={} {}->{} for {}", pending, oldInterestOps, newInterestOps, this);

        if (!pending)
            _selector.submit(_runUpdateKey);
    }


    @Override
    public void close()
    {
        if (_open.compareAndSet(true, false))
        {
            super.close();
            _selector.destroyEndPoint(this);
        }
    }

    @Override
    public boolean isOpen()
    {
        // We cannot rely on super.isOpen(), because there is a race between calls to close() and isOpen():
        // a thread may call close(), which flips the boolean but has not yet called super.close(), and
        // another thread calls isOpen() which would return true - wrong - if based on super.isOpen().
        return _open.get();
    }

    @Override
    public void onOpen()
    {
        if (_open.compareAndSet(false, true))
            super.onOpen();
    }

    @Override
    public String toString()
    {
        // We do a best effort to print the right toString() and that's it.
        try
        {
            boolean valid = _key != null && _key.isValid();
            int keyInterests = valid ? _key.interestOps() : -1;
            int keyReadiness = valid ? _key.readyOps() : -1;
            return String.format("%s{io=%d,kio=%d,kro=%d}",
                    super.toString(),
                    _interestOps,
                    keyInterests,
                    keyReadiness);
        }
        catch (Throwable x)
        {
            return String.format("%s{io=%s,kio=-2,kro=-2}", super.toString(), _interestOps);
        }
    }
}
