/*
 * Copyright 2015 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.performance.tests.network;

import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireKey;
import net.openhft.chronicle.wire.WireOut;

import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * Created by peter.lawrey on 31/01/15.
 */
class TestData implements DoubleConsumer, LongConsumer, IntConsumer {

    int value1;
    long value2;
    double value3;

    public void write(WireOut wire) {
        wire.writeDocument(false, d ->
                d.write(Field.key1).int32(value1)
                        .write(Field.key2).int64(value2)
                        .write(Field.key3).float64(value3));
    }

    public void read(WireIn wire) {
        wire.readDocument(null, data ->
                        data.read(Field.key1).int32(i -> value1 = i)
                                .read(Field.key2).int64(i -> value2 = i)
                                .read(Field.key3).float64(i -> value3 = i)
        );
    }

    @Override
    public void accept(double value) {
        value3 = value;
    }

    @Override
    public void accept(int value) {
    }

    @Override
    public void accept(long value) {
    }

    enum Field implements WireKey {
        key1, key2, key3;
    }
}
