/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.accumulo.model;

import org.apache.accumulo.core.data.Range;
import org.junit.jupiter.api.Test;

import static org.testng.Assert.assertEquals;

public class TestSerializedRange
{
    @Test
    public void testJsonRoundTrip()
    {
        Range exact = new Range("foo");
        Range range = new Range("bar", "foo");
        Range exclusiveRange = new Range("asiago", false, "bagel", false);

        assertEquals(SerializedRange.serialize(exact).deserialize(), exact);
        assertEquals(SerializedRange.serialize(range).deserialize(), range);
        assertEquals(SerializedRange.serialize(exclusiveRange).deserialize(), exclusiveRange);
    }
}
