package dev.tendril.config

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker

/**
 * Project-level settings, stored in .idea/tendril.xml and edited under Settings | Tools | Tendril:
 *  - keys the "unknown property" inspection must skip (custom properties)
 *  - base URLs of Feign services that only declare a name
 *  - extra config keys, tried before the built-in ones, that hold the address of a name-only Feign client
 *  - the projects whose endpoints, Feign clients and fetch calls take part in the service calls of this one
 */
@Service(Service.Level.PROJECT)
@State(name = "TendrilSettings", storages = [Storage("tendril.xml")])
class TendrilSettings : PersistentStateComponent<TendrilSettings.State> {

    class State {
        var ignoredKeys: MutableList<String> = mutableListOf()

        /** Feign service name -> base URL, for clients that only declare a name (resolved by discovery at runtime). */
        var serviceUrls: MutableMap<String, String> = mutableMapOf()

        /** Config keys with a `{name}` placeholder for the service name, tried before the built-in ones. */
        var nameKeyTemplates: MutableList<String> = mutableListOf()

        /** Root folders of other projects (backend, BFF, frontend) linked to this one for the service calls. */
        var linkedProjects: MutableList<String> = mutableListOf()
    }

    private var state = State()

    /** Bumped when a setting that cached scans depend on changes (the Feign key templates). */
    val tracker = SimpleModificationTracker()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        tracker.incModificationCount()
    }

    /** An entry ignores the key itself and everything below it ("jwt" ignores "jwt.secret"). */
    fun isIgnored(key: String): Boolean =
        state.ignoredKeys.any { key == it || key.startsWith("$it.") }

    fun ignore(key: String) {
        if (key !in state.ignoredKeys) state.ignoredKeys.add(key)
    }

    /** Call after changing [state] in place. */
    fun changed() {
        tracker.incModificationCount()
    }

    companion object {
        fun getInstance(project: Project): TendrilSettings = project.service()
    }
}
