/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.transforms.reflect;

import static org.apache.beam.sdk.transforms.reflect.DoFnSignaturesTestUtils.analyzeProcessElementMethod;
import static org.apache.beam.sdk.transforms.reflect.DoFnSignaturesTestUtils.errors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.reflect.TypeToken;
import java.util.List;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.BoundedPerElement;
import org.apache.beam.sdk.transforms.DoFn.UnboundedPerElement;
import org.apache.beam.sdk.transforms.reflect.DoFnSignaturesTestUtils.AnonymousMethod;
import org.apache.beam.sdk.transforms.reflect.DoFnSignaturesTestUtils.FakeDoFn;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link DoFnSignatures} focused on methods related to <a
 * href="https://s.apache.org/splittable-do-fn">splittable</a> {@link DoFn}.
 */
@SuppressWarnings("unused")
@RunWith(JUnit4.class)
public class DoFnSignaturesSplittableDoFnTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  private static class SomeRestriction {}

  private abstract static class SomeRestrictionTracker
      implements RestrictionTracker<SomeRestriction> {}

  private abstract static class SomeRestrictionCoder implements Coder<SomeRestriction> {}

  @Test
  public void testReturnsProcessContinuation() throws Exception {
    DoFnSignature.ProcessElementMethod signature =
        analyzeProcessElementMethod(
            new AnonymousMethod() {
              private DoFn.ProcessContinuation method(
                  DoFn<Integer, String>.ProcessContext context) {
                return null;
              }
            });

    assertTrue(signature.hasReturnValue());
  }

  @Test
  public void testHasRestrictionTracker() throws Exception {
    DoFnSignature.ProcessElementMethod signature =
        analyzeProcessElementMethod(
            new AnonymousMethod() {
              private void method(
                  DoFn<Integer, String>.ProcessContext context, SomeRestrictionTracker tracker) {}
            });

    assertTrue(signature.isSplittable());
    assertTrue(signature.extraParameters().contains(DoFnSignature.Parameter.RESTRICTION_TRACKER));
    assertEquals(SomeRestrictionTracker.class, signature.trackerT().getRawType());
  }

  @Test
  public void testSplittableProcessElementMustNotHaveOtherParams() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("must not have any extra context arguments");
    thrown.expectMessage("BOUNDED_WINDOW");

    DoFnSignature.ProcessElementMethod signature =
        analyzeProcessElementMethod(
            new AnonymousMethod() {
              private void method(
                  DoFn<Integer, String>.ProcessContext context,
                  SomeRestrictionTracker tracker,
                  BoundedWindow window) {}
            });
  }

  @Test
  public void testInfersBoundednessFromAnnotation() throws Exception {
    class BaseSplittableFn extends DoFn<Integer, String> {
      @ProcessElement
      public void processElement(ProcessContext context, SomeRestrictionTracker tracker) {}

      @GetInitialRestriction
      public SomeRestriction getInitialRestriction(Integer element) {
        return null;
      }

      @NewTracker
      public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
        return null;
      }
    }

    @BoundedPerElement
    class BoundedSplittableFn extends BaseSplittableFn {}

    @UnboundedPerElement
    class UnboundedSplittableFn extends BaseSplittableFn {}

    assertEquals(
        PCollection.IsBounded.BOUNDED,
        DoFnSignatures.INSTANCE
            .getOrParseSignature(BaseSplittableFn.class)
            .isBoundedPerElement());
    assertEquals(
        PCollection.IsBounded.BOUNDED,
        DoFnSignatures.INSTANCE
            .getOrParseSignature(BoundedSplittableFn.class)
            .isBoundedPerElement());
    assertEquals(
        PCollection.IsBounded.UNBOUNDED,
        DoFnSignatures.INSTANCE
            .getOrParseSignature(UnboundedSplittableFn.class)
            .isBoundedPerElement());
  }

  @Test
  public void testUnsplittableIsBounded() throws Exception {
    class UnsplittableFn extends DoFn<Integer, String> {
      @ProcessElement
      public void process(ProcessContext context) {}
    }
    assertEquals(
        PCollection.IsBounded.BOUNDED,
        DoFnSignatures.INSTANCE
            .getOrParseSignature(UnsplittableFn.class)
            .isBoundedPerElement());
  }

  private static class BaseFnWithContinuation extends DoFn<Integer, String> {
    @ProcessElement
    public ProcessContinuation processElement(
        ProcessContext context, SomeRestrictionTracker tracker) {
      return null;
    }

    @GetInitialRestriction
    public SomeRestriction getInitialRestriction(Integer element) {
      return null;
    }

    @NewTracker
    public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
      return null;
    }
  }

  @Test
  public void testSplittableIsBoundedByDefault() throws Exception {
    assertEquals(
        PCollection.IsBounded.UNBOUNDED,
        DoFnSignatures.INSTANCE
            .getOrParseSignature(BaseFnWithContinuation.class)
            .isBoundedPerElement());
  }

  @Test
  public void testSplittableRespectsBoundednessAnnotation() throws Exception {
    @BoundedPerElement
    class BoundedFnWithContinuation extends BaseFnWithContinuation {}

    assertEquals(
        PCollection.IsBounded.BOUNDED,
        DoFnSignatures.INSTANCE
            .getOrParseSignature(BoundedFnWithContinuation.class)
            .isBoundedPerElement());

    @UnboundedPerElement
    class UnboundedFnWithContinuation extends BaseFnWithContinuation {}

    assertEquals(
        PCollection.IsBounded.UNBOUNDED,
        DoFnSignatures.INSTANCE
            .getOrParseSignature(UnboundedFnWithContinuation.class)
            .isBoundedPerElement());
  }

  @Test
  public void testUnsplittableButDeclaresBounded() throws Exception {
    @BoundedPerElement
    class SomeFn extends DoFn<Integer, String> {
      @ProcessElement
      public void process(ProcessContext context) {}
    }

    thrown.expectMessage("Non-splittable, but annotated as @Bounded");
    DoFnSignatures.INSTANCE.getOrParseSignature(SomeFn.class);
  }

  @Test
  public void testUnsplittableButDeclaresUnbounded() throws Exception {
    @UnboundedPerElement
    class SomeFn extends DoFn<Integer, String> {
      @ProcessElement
      public void process(ProcessContext context) {}
    }

    thrown.expectMessage("Non-splittable, but annotated as @Unbounded");
    DoFnSignatures.INSTANCE.getOrParseSignature(SomeFn.class);
  }

  /** Tests a splittable {@link DoFn} that defines all methods in their full form, correctly. */
  @Test
  public void testSplittableWithAllFunctions() throws Exception {
    class GoodSplittableDoFn extends DoFn<Integer, String> {
      @ProcessElement
      public ProcessContinuation processElement(
          ProcessContext context, SomeRestrictionTracker tracker) {
        return null;
      }

      @GetInitialRestriction
      public SomeRestriction getInitialRestriction(Integer element) {
        return null;
      }

      @SplitRestriction
      public void splitRestriction(
          Integer element, SomeRestriction restriction, OutputReceiver<SomeRestriction> receiver) {}

      @NewTracker
      public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
        return null;
      }

      @GetRestrictionCoder
      public SomeRestrictionCoder getRestrictionCoder() {
        return null;
      }
    }

    DoFnSignature signature = DoFnSignatures.INSTANCE.getOrParseSignature(GoodSplittableDoFn.class);
    assertEquals(SomeRestrictionTracker.class, signature.processElement().trackerT().getRawType());
    assertTrue(signature.processElement().isSplittable());
    assertTrue(signature.processElement().hasReturnValue());
    assertEquals(
        SomeRestriction.class, signature.getInitialRestriction().restrictionT().getRawType());
    assertEquals(SomeRestriction.class, signature.splitRestriction().restrictionT().getRawType());
    assertEquals(SomeRestrictionTracker.class, signature.newTracker().trackerT().getRawType());
    assertEquals(SomeRestriction.class, signature.newTracker().restrictionT().getRawType());
    assertEquals(SomeRestrictionCoder.class, signature.getRestrictionCoder().coderT().getRawType());
  }

  /**
   * Tests a splittable {@link DoFn} that defines all methods in their full form, correctly, using
   * generic types.
   */
  @Test
  public void testSplittableWithAllFunctionsGeneric() throws Exception {
    class GoodGenericSplittableDoFn<RestrictionT, TrackerT, CoderT> extends DoFn<Integer, String> {
      @ProcessElement
      public ProcessContinuation processElement(ProcessContext context, TrackerT tracker) {
        return null;
      }

      @GetInitialRestriction
      public RestrictionT getInitialRestriction(Integer element) {
        return null;
      }

      @SplitRestriction
      public void splitRestriction(
          Integer element, RestrictionT restriction, OutputReceiver<RestrictionT> receiver) {}

      @NewTracker
      public TrackerT newTracker(RestrictionT restriction) {
        return null;
      }

      @GetRestrictionCoder
      public CoderT getRestrictionCoder() {
        return null;
      }
    }

    DoFnSignature signature =
        DoFnSignatures.INSTANCE.getOrParseSignature(
            new GoodGenericSplittableDoFn<
                SomeRestriction, SomeRestrictionTracker, SomeRestrictionCoder>() {}.getClass());
    assertEquals(SomeRestrictionTracker.class, signature.processElement().trackerT().getRawType());
    assertTrue(signature.processElement().isSplittable());
    assertTrue(signature.processElement().hasReturnValue());
    assertEquals(
        SomeRestriction.class, signature.getInitialRestriction().restrictionT().getRawType());
    assertEquals(SomeRestriction.class, signature.splitRestriction().restrictionT().getRawType());
    assertEquals(SomeRestrictionTracker.class, signature.newTracker().trackerT().getRawType());
    assertEquals(SomeRestriction.class, signature.newTracker().restrictionT().getRawType());
    assertEquals(SomeRestrictionCoder.class, signature.getRestrictionCoder().coderT().getRawType());
  }

  @Test
  public void testSplittableMissingRequiredMethods() throws Exception {
    class BadFn extends DoFn<Integer, String> {
      @ProcessElement
      public void process(ProcessContext context, SomeRestrictionTracker tracker) {}
    }

    thrown.expectMessage(
        "Splittable, but does not define the following required methods: "
            + "[@GetInitialRestriction, @NewTracker]");
    DoFnSignatures.INSTANCE.getOrParseSignature(BadFn.class);
  }

  @Test
  public void testNewTrackerReturnsWrongType() throws Exception {
    class BadFn extends DoFn<Integer, String> {
      @ProcessElement
      public void process(ProcessContext context, SomeRestrictionTracker tracker) {}

      @NewTracker
      public void newTracker(SomeRestriction restriction) {}

      @GetInitialRestriction
      public SomeRestriction getInitialRestriction(Integer element) {
        return null;
      }
    }

    thrown.expectMessage(
        "Returns void, but must return a subtype of RestrictionTracker<SomeRestriction>");
    DoFnSignatures.INSTANCE.getOrParseSignature(BadFn.class);
  }

  @Test
  public void testGetInitialRestrictionMismatchesNewTracker() throws Exception {
    class BadFn extends DoFn<Integer, String> {
      @ProcessElement
      public void process(ProcessContext context, SomeRestrictionTracker tracker) {}

      @NewTracker
      public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
        return null;
      }

      @GetInitialRestriction
      public String getInitialRestriction(Integer element) {
        return null;
      }
    }

    thrown.expectMessage(
        "getInitialRestriction(Integer): Uses restriction type String, but @NewTracker method");
    thrown.expectMessage("newTracker(SomeRestriction) uses restriction type SomeRestriction");
    DoFnSignatures.INSTANCE.getOrParseSignature(BadFn.class);
  }

  @Test
  public void testGetRestrictionCoderReturnsWrongType() throws Exception {
    class BadFn extends DoFn<Integer, String> {
      @ProcessElement
      public void process(ProcessContext context, SomeRestrictionTracker tracker) {}

      @NewTracker
      public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
        return null;
      }

      @GetInitialRestriction
      public SomeRestriction getInitialRestriction(Integer element) {
        return null;
      }

      @GetRestrictionCoder
      public KvCoder getRestrictionCoder() {
        return null;
      }
    }

    thrown.expectMessage(
        "getRestrictionCoder() returns KvCoder which is not a subtype of Coder<SomeRestriction>");
    DoFnSignatures.INSTANCE.getOrParseSignature(BadFn.class);
  }

  @Test
  public void testSplitRestrictionReturnsWrongType() throws Exception {
    thrown.expectMessage(
        "Third argument must be OutputReceiver<SomeRestriction>, but is OutputReceiver<String>");
    DoFnSignatures.analyzeSplitRestrictionMethod(
        errors(),
        TypeToken.of(FakeDoFn.class),
        new AnonymousMethod() {
          void method(
              Integer element, SomeRestriction restriction, DoFn.OutputReceiver<String> receiver) {}
        }.getMethod(),
        TypeToken.of(Integer.class));
  }

  @Test
  public void testSplitRestrictionWrongElementArgument() throws Exception {
    class BadFn {
      private List<SomeRestriction> splitRestriction(String element, SomeRestriction restriction) {
        return null;
      }
    }

    thrown.expectMessage("First argument must be the element type Integer");
    DoFnSignatures.analyzeSplitRestrictionMethod(
        errors(),
        TypeToken.of(FakeDoFn.class),
        new AnonymousMethod() {
          void method(
              String element,
              SomeRestriction restriction,
              DoFn.OutputReceiver<SomeRestriction> receiver) {}
        }.getMethod(),
        TypeToken.of(Integer.class));
  }

  @Test
  public void testSplitRestrictionWrongNumArguments() throws Exception {
    thrown.expectMessage("Must have exactly 3 arguments");
    DoFnSignatures.analyzeSplitRestrictionMethod(
        errors(),
        TypeToken.of(FakeDoFn.class),
        new AnonymousMethod() {
          private void method(
              Integer element,
              SomeRestriction restriction,
              DoFn.OutputReceiver<SomeRestriction> receiver,
              Object extra) {}
        }.getMethod(),
        TypeToken.of(Integer.class));
  }

  @Test
  public void testSplitRestrictionConsistentButWrongType() throws Exception {
    class OtherRestriction {}

    class BadFn extends DoFn<Integer, String> {
      @ProcessElement
      public void process(ProcessContext context, SomeRestrictionTracker tracker) {}

      @NewTracker
      public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
        return null;
      }

      @GetInitialRestriction
      public SomeRestriction getInitialRestriction(Integer element) {
        return null;
      }

      @DoFn.SplitRestriction
      public void splitRestriction(
          Integer element,
          OtherRestriction restriction,
          OutputReceiver<OtherRestriction> receiver) {}
    }

    thrown.expectMessage(
        "getInitialRestriction(Integer): Uses restriction type SomeRestriction, "
            + "but @SplitRestriction method ");
    thrown.expectMessage(
        "splitRestriction(Integer, OtherRestriction, OutputReceiver) "
            + "uses restriction type OtherRestriction");
    DoFnSignatures.INSTANCE.getOrParseSignature(BadFn.class);
  }

  @Test
  public void testUnsplittableMustNotDefineExtraMethods() throws Exception {
    class BadFn extends DoFn<Integer, String> {
      @ProcessElement
      public void processElement(ProcessContext context) {}

      @GetInitialRestriction
      public SomeRestriction getInitialRestriction(Integer element) {
        return null;
      }

      @SplitRestriction
      public void splitRestriction(
          Integer element, SomeRestriction restriction, OutputReceiver<SomeRestriction> receiver) {}

      @NewTracker
      public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
        return null;
      }

      @GetRestrictionCoder
      public SomeRestrictionCoder getRestrictionCoder() {
        return null;
      }
    }

    thrown.expectMessage(
        "Non-splittable, but defines methods: "
            + "[@GetInitialRestriction, @SplitRestriction, @NewTracker, @GetRestrictionCoder]");
    DoFnSignatures.INSTANCE.getOrParseSignature(BadFn.class);
  }

  @Test
  public void testNewTrackerWrongNumArguments() throws Exception {
    thrown.expectMessage("Must have a single argument");
    DoFnSignatures.analyzeNewTrackerMethod(
        errors(),
        TypeToken.of(FakeDoFn.class),
        new AnonymousMethod() {
          private SomeRestrictionTracker method(SomeRestriction restriction, Object extra) {
            return null;
          }
        }.getMethod());
  }

  @Test
  public void testNewTrackerInconsistent() throws Exception {
    thrown.expectMessage(
        "Returns SomeRestrictionTracker, but must return a subtype of RestrictionTracker<String>");
    DoFnSignatures.analyzeNewTrackerMethod(
        errors(),
        TypeToken.of(FakeDoFn.class),
        new AnonymousMethod() {
          private SomeRestrictionTracker method(String restriction) {
            return null;
          }
        }.getMethod());
  }
}