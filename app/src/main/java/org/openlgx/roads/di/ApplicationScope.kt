package org.openlgx.roads.di

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
