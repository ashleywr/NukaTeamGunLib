package com.nukateam.ntgl.modules.datapack;

import com.google.gson.JsonSyntaxException;
import com.nukateam.chassis_core.ChassisCore;
import com.nukateam.ntgl.Ntgl;
import com.nukateam.ntgl.common.data.json.JsonDeserializers;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InvalidObjectException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class ConfigUtils {
    private static final int FILE_TYPE_LENGTH_VALUE = ".json".length();

    /**
     * Orders the config files found for one registry id so that ChassisCore's file is
     * applied first and a mod's own file, applied after it, wins.
     * <p>
     * This replaces an inline comparator that returned {@code -1} for every pair of
     * distinct non-ChassisCore namespaces, in both directions, which breaks the
     * {@link Comparator} contract. The lists involved are short enough that TimSort never
     * noticed, but the ordering is now well defined.
     */
    private static final Comparator<ResourceLocation> CHASSIS_CORE_FIRST =
            Comparator.comparingInt(location -> location.getNamespace().equals(ChassisCore.MOD_ID) ? 0 : 1);

    public static<T, Y, R> Map<T, Y> getConfigMap(ResourceManager manager, Registry<R> registry, Predicate<R> filter, Class<Y> yClass, String resourcePath) {
        var map = new HashMap<T, Y>();
        var stream = registry.stream();
        var filtered = stream.filter(filter);
        var startedAt = System.nanoTime();

        // Walk the resource tree once for the whole registry, rather than once per entry.
        //
        // ResourceManager.listResources() walks every pack in the stack, so calling it from
        // inside the loop below made this O(entries x packs x files). NetworkEquipmentManager
        // and NetworkChassisManager both pass a `v -> true` predicate, so that was one full
        // walk of every datapack for every item, and for every entity type, in the game.
        var configsByFileName = indexByFileName(manager, resourcePath);

        try {
            filtered.forEach(item ->
            {
                var id = registry.getKey(item);

                if (id != null) {
                    // A hit in the index is already an exact file-name match, which is what
                    // the old endsWith(id + ".json") filter plus its follow-up name check
                    // amounted to. That filter also matched wider than intended -
                    // guns/mega_pistol.json ends with pistol.json - and threw the extras
                    // away afterwards; keying on the file name selects the same set directly.
                    var resources = configsByFileName.get(id.getPath());
                    if (resources == null) return;

                    resources.forEach(resourceLocation ->
                    {
                        // The file's namespace must still match the item's registered one.
                        if (!id.getNamespace().equals(resourceLocation.getNamespace()))
                            return;

                        manager.getResource(resourceLocation).ifPresent(resource ->
                        {
                            try (var reader = new BufferedReader(new InputStreamReader(resource.open(), StandardCharsets.UTF_8))) {
                                var gun = GsonHelper.fromJson(JsonDeserializers.GSON_INSTANCE, reader, yClass);

                                if (true /*Validator.isValidObject(gun)*/) {
                                    map.put((T) item, gun);
                                } else {
                                    Ntgl.LOGGER.error("Couldn't load data file {} as it is missing or malformed. Using default gun data", resourceLocation);
                                    map.putIfAbsent((T) item, yClass.getDeclaredConstructor().newInstance());
                                }
                            } catch (InvalidObjectException e) {
                                Ntgl.LOGGER.error("Missing required properties for {}", resourceLocation);
                                e.printStackTrace();
                            } catch (IOException | InvocationTargetException | InstantiationException |
                                     NoSuchMethodException e) {
                                Ntgl.LOGGER.error("Couldn't parse data file {}", resourceLocation);
                            } catch (IllegalAccessException | IllegalStateException | JsonSyntaxException e) {
                                Ntgl.LOGGER.error("Wrong data for {}", resourceLocation);
                                e.printStackTrace();
                            } catch (Exception e) {
                                Ntgl.LOGGER.error("Something wrong with resource {}", resourceLocation);
                                Ntgl.LOGGER.error(e.getMessage(), e);
                                e.printStackTrace();
                            }
                        });
                    });
                }
            });
        }
        catch (Exception e){
            Ntgl.LOGGER.error("stream: {}", stream);
            Ntgl.LOGGER.error("filtered: {}", stream);
            Ntgl.LOGGER.error(e.getMessage(), e);
            e.printStackTrace();
        }

        // The previous cost showed up in the log only as a long silence, so make the work
        // this method does measurable without a profiler attached.
        Ntgl.LOGGER.debug("Loaded {} config(s) from '{}' in {} ms, from {} candidate file name(s)",
                map.size(), resourcePath, (System.nanoTime() - startedAt) / 1_000_000L, configsByFileName.size());

        return map;
    }

    /**
     * Walks {@code path} once and groups every JSON file below it by bare file name, so that
     * a registry id can be resolved to its config files with a map lookup instead of a fresh
     * walk of the whole pack stack.
     */
    @NotNull
    private static Map<String, List<ResourceLocation>> indexByFileName(ResourceManager manager, String path) {
        var index = new HashMap<String, List<ResourceLocation>>();

        for (var location : manager.listResources(path, file -> file.getPath().endsWith(".json")).keySet()) {
            var filePath = location.getPath();
            var fileName = filePath.substring(filePath.lastIndexOf('/') + 1,
                    filePath.length() - FILE_TYPE_LENGTH_VALUE);
            index.computeIfAbsent(fileName, key -> new ArrayList<>()).add(location);
        }

        // Sorted once per file name rather than once per registry entry: the order depends
        // only on the namespaces of the files, never on the item being resolved.
        index.values().forEach(locations -> locations.sort(CHASSIS_CORE_FIRST));

        return index;
    }
}
