package dev.tendril.batch

import dev.tendril.SpringTestCase

class BatchJobsTest : SpringTestCase() {
    fun `test detects batch and links a job to its steps and components`() {
        assertFalse(BatchJobs.isPresent(project))
        myFixture.addClass("package org.springframework.batch.core; public interface Job {}")
        myFixture.addClass("package org.springframework.batch.core; public interface Step {}")
        assertTrue(BatchJobs.isPresent(project))
        myFixture.addClass("""
            package org.springframework.batch.core.job.builder;
            import org.springframework.batch.core.*;
            public class JobBuilder {
                public JobBuilder start(Step step) { return this; }
                public JobBuilder next(Step step) { return this; }
                public Job build() { return null; }
            }
        """.trimIndent())
        myFixture.addClass("""
            package org.springframework.batch.core.step.builder;
            import org.springframework.batch.core.Step;
            public class StepBuilder {
                public StepBuilder tasklet(Runnable task) { return this; }
                public Step build() { return null; }
            }
        """.trimIndent())
        myFixture.addClass("""
            package app;
            import org.springframework.batch.core.*;
            import org.springframework.batch.core.job.builder.JobBuilder;
            import org.springframework.batch.core.step.builder.StepBuilder;
            import org.springframework.context.annotation.Bean;
            public class BatchConfiguration {
                @Bean Job importJob(Step prepare, Step finish) {
                    return new JobBuilder().start(prepare).next(finish).build();
                }
                @Bean Step prepare(Runnable load) {
                    return new StepBuilder().tasklet(load).build();
                }
                @Bean Step finish() { return null; }
                @Bean Step standalone() { return null; }
                @Bean Runnable load() { return () -> {}; }
            }
        """.trimIndent())
        val (jobs, steps) = BatchJobs.scan(project)
        val job = jobs.single()
        assertEquals("importJob", job.label)
        assertEquals(listOf("prepare", "finish"), job.children.map { it.label })
        val tasklet = job.children.first().children.single()
        assertEquals("load", tasklet.label)
        assertEquals("Tasklet", tasklet.role)
        assertTrue(tasklet.target.element?.text?.contains("Runnable load()") == true)
        assertEquals(listOf("standalone"), steps.map { it.label })
    }
}
