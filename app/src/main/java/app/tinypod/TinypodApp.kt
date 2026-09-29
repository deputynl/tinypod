package app.tinypod

import android.app.Application
import app.tinypod.data.TinypodDatabase

/** Holds app-wide singletons; manual wiring instead of a DI framework. */
class TinypodApp : Application() {
  val database: TinypodDatabase by lazy { TinypodDatabase.create(this) }
}
