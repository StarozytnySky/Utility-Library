package org.broken.arrow.library.itemcreator;


import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.broken.arrow.library.logging.Logging;
import org.broken.arrow.library.logging.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.SkullType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * A library for the Bukkit API to create player skulls
 * from names, base64 strings, and texture URLs.
 * <p>
 * Does not use any NMS code, and should work across all versions.
 *
 * @author Dean B on 12/28/2016.
 */
public class SkullCreator {
    private static final Logging LOG = new Logging(SkullCreator.class);
    private static final CheckClassesExist checks = new CheckClassesExist();

    private static final String PLAYER_HEAD = "PLAYER_HEAD";
    private static final String BLOCK = "block";
    private static final String NAME = "name";
    public static final String PROFILE = "profile";
    public static final String TEXTURES = "textures";

    // some reflection stuff to be used when setting a skull's profile
    private static Field blockProfileField;
    private static Method metaSetProfileMethod;
    private static Field metaProfileField;

    // Check if its legacy version, means before 1.13.
    static {
    }

    /**
     * Private constructor to prevent instantiation.
     */
    private SkullCreator() {
    }

    /**
     * Creates a player skull, should work in both legacy and new Bukkit APIs.
     *
     * @return Returns the itemStack instance or null.
     */
    @Nullable
    public static ItemStack createSkull() {
        try {
            return new ItemStack(Material.valueOf(PLAYER_HEAD));
        } catch (IllegalArgumentException e) {
            final Material skullItem = getMaterial("SKULL_ITEM");
            if (skullItem == null)
                return null;
            return new ItemStack(skullItem, 1, (byte) 3);
        }
    }

    /**
     * Creates a player skull item with the skin based on a player's name.
     *
     * @param name The Player's name.
     * @return The head of the Player.
     * @deprecated names don't make for good identifiers.
     */
    @Deprecated
    public static ItemStack itemFromName(String name) {
        final ItemStack skull = createSkull();
        checkNull(skull, "[create skull]", "Failed to find the skull material.");

        return itemWithName(skull, name);
    }

    /**
     * Creates a player skull item with the skin based on a player's UUID.
     *
     * @param id The Player's UUID.
     * @return The head of the Player.
     */
    public static ItemStack itemFromUuid(UUID id) {
        final ItemStack skull = createSkull();
        checkNull(skull, "[create skull]", "Failed to find the skull material.");
        return itemWithUuid(skull, id);
    }

    /**
     * Creates a player skull item with the skin at a Mojang URL.
     *
     * @param url The Mojang URL.
     * @return The head of the Player.
     */
    public static ItemStack itemFromUrl(String url) {
        final ItemStack skull = createSkull();
        checkNull(skull, "[create skull]", "Failed to find the skull material.");
        return itemWithUrl(skull, url);
    }

    /**
     * Creates a player skull item with the skin based on a base64 string.
     *
     * @param base64 The Mojang URL.
     * @return The head of the Player.
     */
    public static ItemStack itemFromBase64(String base64) {
        final ItemStack skull = createSkull();
        checkNull(skull, "[create skull]", "Failed to find the skull material.");
        return itemWithBase64(skull, base64);
    }

    /**
     * Modifies a skull to use the skin of the player with a given name.
     *
     * @param item The item to apply the name to. Must be a player skull.
     * @param name The Player's name.
     * @return The head of the Player.
     * @deprecated names don't make for good identifiers.
     */
    @Deprecated
    public static ItemStack itemWithName(@Nonnull final ItemStack item, @Nonnull final String name) {
        notNull(item, "item");
        notNull(name, NAME);

        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwner(name);
        item.setItemMeta(meta);

        return item;
    }

    /**
     * Modifies a skull to use the skin of the player with a given UUID.
     *
     * @param item The item to apply the name to. Must be a player skull.
     * @param id   The Player's UUID.
     * @return The head of the Player.
     */
    public static ItemStack itemWithUuid(@Nonnull final ItemStack item, @Nonnull final UUID id) {
        notNull(item, "item");
        notNull(id, "id");

        SkullMeta meta = (SkullMeta) item.getItemMeta();
        setOwningPlayer(meta, Bukkit.getOfflinePlayer(id));
        item.setItemMeta(meta);

        return item;
    }

    /**
     * Modifies a skull to use the skin at the given Mojang URL.
     *
     * @param item The item to apply the skin to. Must be a player skull.
     * @param url  The URL of the Mojang skin.
     * @return The head associated with the URL.
     */
    public static ItemStack itemWithUrl(@Nonnull final ItemStack item, @Nonnull final String url) {
        notNull(item, "item");
        notNull(url, "url");

        final ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof SkullMeta)) {
            return null;
        }

        SkullMeta meta = (SkullMeta) itemMeta;
        UUID randomId = UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
        setSkullUrl(meta, randomId, url);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Modifies a skull to use the skin based on the given base64 string.
     *
     * @param item   The ItemStack to put the base64 onto. Must be a player skull.
     * @param base64 The base64 string containing the texture.
     * @return The head with a custom texture.
     */
    public static ItemStack itemWithBase64(@Nonnull final ItemStack item, @Nonnull final String base64) {
        notNull(item, "item");
        notNull(base64, "base64");

        if (!(item.getItemMeta() instanceof SkullMeta)) {
            return null;
        }
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        mutateItemMeta(meta, base64);
        item.setItemMeta(meta);

        return item;
    }

    /**
     * Sets the block to a skull with the given name. Recommending to use
     * {@link #blockWithUuid(Block, UUID)} as this creates performance penitently.
     *
     * @param block The block to set.
     * @param name  The player to set it to.
     * @deprecated names don't make for good identifiers.
     */
    @Deprecated
    public static void blockWithName(@Nonnull final Block block, @Nonnull final String name) {
        notNull(block, BLOCK);
        notNull(name, NAME);

        Skull state = (Skull) block.getState();
        state.setOwningPlayer(Bukkit.getOfflinePlayer(name));
        state.update(false, false);
    }

    /**
     * Sets the block to a skull with the given UUID.
     *
     * @param block The block to set.
     * @param id    The player to set it to.
     */
    public static void blockWithUuid(@Nonnull final Block block, @Nonnull final UUID id) {
        notNull(block, BLOCK);
        notNull(id, "id");

        setToSkull(block);
        Skull state = (Skull) block.getState();
        state.setOwningPlayer(Bukkit.getOfflinePlayer(id));
        state.update(false, false);
    }

    /**
     * Sets the block to a skull with the skin found at the provided mojang URL.
     * <p>
     * The URL must point to the Minecraft texture server. Example URL:
     * <a href="http://textures.minecraft.net/texture/b3fbd454b599df593f57101bfca34e67d292a8861213d2202bb575da7fd091ac">
     * http://textures.minecraft.net/texture/b3fbd454b599df593f57101bfca34e67d292a8861213d2202bb575da7fd091ac</a>
     *
     * @param block The block to set.
     * @param url   The mojang URL to retrieve the texture.
     */
    public static void blockWithUrl(@Nonnull final Block block, @Nonnull final String url) {
        notNull(block, BLOCK);
        notNull(url, "url");

        setToSkull(block);
        final UUID id = UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
        blockWithUrl(block, id, url);
    }

    /**
     * Sets the block to a skull with the skin found at the provided mojang URL.
     * <p>
     * The URL must point to the Minecraft texture server. Example URL:
     * <a href="http://textures.minecraft.net/texture/b3fbd454b599df593f57101bfca34e67d292a8861213d2202bb575da7fd091ac">
     * http://textures.minecraft.net/texture/b3fbd454b599df593f57101bfca34e67d292a8861213d2202bb575da7fd091ac</a>
     *
     * @param block The block to set.
     * @param id    The player to set it to.
     * @param url   The mojang URL to retrieve the texture.
     */
    public static void blockWithUrl(@Nonnull final Block block, @Nonnull final UUID id, @Nullable final String url) {
        // Get old block data
        final BlockState skullState = block.getState();
        if (!(skullState instanceof Skull)) return;

        if (checks.isDoesHavePlayerProfile()) {
            PlayerProfile profile = Bukkit.createPlayerProfile(id);
            if (url != null) {
                try {
                    profile.getTextures().setSkin(new URL(url));
                } catch (MalformedURLException e) {
                    LOG.log(() -> "Can't set back the url '" + url + "' for this skull.");
                }
            }
            ((Skull) skullState).setOwnerProfile(profile);
            return;
        }
        Skull skull = (Skull) skullState;
        try {
            GameProfile profile = new GameProfile(id, "aaaa");
            setSkin(profile, url);
            Method setProfileMethod = skull.getClass().getDeclaredMethod("setProfile", GameProfile.class);
            setProfileMethod.setAccessible(true);
            setProfileMethod.invoke(skull, profile);
            skull.update(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * Sets the block to a skull with the skin for the base64 string.
     *
     * @param block  The block to set.
     * @param base64 The base64 to set it to use.
     */
    public static void blockWithBase64(@Nonnull final Block block, @Nonnull final String base64) {
        notNull(block, BLOCK);
        notNull(base64, "base64");

        setToSkull(block);
        Skull state = (Skull) block.getState();
        mutateBlockState(state, base64);
        state.update(false, false);
    }


    /**
     * Sets the skin URL on the given {@link SkullMeta}.
     *
     * <p>This method applies a skin to a skull item, supporting both modern
     * and legacy server implementations:</p>
     * <ul>
     *     <li>Modern API (PlayerProfile-based): uses {@link PlayerProfile} and {@code setOwnerProfile}.</li>
     *     <li>Legacy API (GameProfile reflection): creates a {@link GameProfile} and sets the
     *         Base64-encoded texture via the internal {@code profile} field.</li>
     * </ul>
     *
     * <p>If {@code url} is {@code null}, only the {@code uuid} will be applied,
     * resulting in a skull that displays the player's default skin.</p>
     *
     * @param meta the {@link SkullMeta} to modify
     * @param uuid the UUID to associate with the skull's profile
     * @param url  the skin URL to apply, or {@code null} to leave only the default player skin
     */
    public static void setSkullUrl(@Nonnull final SkullMeta meta, @Nonnull final UUID uuid, @Nullable final String url) {
        if (checks.isDoesHaveOwnerProfile()) {
            PlayerProfile profile = Bukkit.createPlayerProfile(uuid);
            if (url != null) {
                try {
                    profile.getTextures().setSkin(new URL(url));
                } catch (MalformedURLException e) {
                    LOG.log(() -> "Can't set back the url '" + url + "' for this skull.");
                }
            }
            meta.setOwnerProfile(profile);
            return;
        }
        GameProfile profile = new GameProfile(uuid, "aaaa");
        setSkin(profile, url);
        try {
            if (metaProfileField == null) {
                metaProfileField = meta.getClass().getDeclaredField(PROFILE);
                metaProfileField.setAccessible(true);
            }
            metaProfileField.set(meta, profile);
        } catch (NoSuchFieldException e) {
            LOG.log(e, () -> "Failed to access legacy SkullMeta profile field.");
        } catch (IllegalAccessException e) {
            LOG.log(e, () -> "Failed to set the legacy SkullMeta profile to field.");
        }
    }

    /**
     * Retrieves the skin URL from the given {@link SkullMeta}.
     * <p>
     * This method attempts to extract the URL of the custom head texture
     * regardless of whether the server is running a modern API
     * (using {@link PlayerProfile}) or an older legacy API
     * (via the internal {@code GameProfile}).
     * </p>
     *
     * @param meta the {@link SkullMeta} to extract the skin URL from
     * @return the skin URL in string form, or {@code null} if no URL is set
     * or if the required methods/fields could not be accessed.
     */
    @Nullable
    public static String getSkullUrl(SkullMeta meta) {
        if (checks.isDoesHaveOwnerProfile()) {
            try {
                final PlayerProfile ownerProfile = meta.getOwnerProfile();
                if (ownerProfile != null) {
                    final URL skin = ownerProfile.getTextures().getSkin();
                    if (skin != null)
                        return skin.toExternalForm();
                }
            } catch (NoClassDefFoundError | NoSuchMethodError ex2) {
                LOG.log(() -> "Could not invoke PlayerProfile and the methods (getTextures, getOwnerProfile, getSkin).");
            }
            return null;
        }
        try {
            if (metaProfileField == null) {
                metaProfileField = meta.getClass().getDeclaredField(PROFILE);
                metaProfileField.setAccessible(true);
            }
            GameProfile profile = (GameProfile) metaProfileField.get(meta);
            if (profile != null) {
                String url = getUrl(profile);
                if (url != null) return url;
            }
        } catch (NoSuchFieldException | IllegalAccessException ex2) {
            LOG.log(ex2, () -> "Failed to access legacy SkullMeta profile field.");
        }
        return null;
    }

    /**
     * Sets the block to a skull of type PLAYER_HEAD or legacy SKULL with player skull type.
     *
     * @param block The block to convert to a skull.
     */
    private static void setToSkull(Block block) {
        try {
            block.setType(Material.valueOf(PLAYER_HEAD), false);
        } catch (IllegalArgumentException e) {
            block.setType(Material.valueOf("SKULL"), false);
            Skull state = (Skull) block.getState();
            state.setSkullType(SkullType.PLAYER);
            state.update(false, false);
        }
    }

    /**
     * Checks if an object is null and throws NullPointerException if so.
     *
     * @param o    The object to check.
     * @param name The name of the object to use in the exception message.
     * @throws NullPointerException if {@code o} is null.
     */
    private static void notNull(Object o, String name) {
        if (o == null) {
            throw new NullPointerException(name + " should not be null!");
        }
    }

    /**
     * Checks if an object is null and throws RuntimeException if so.
     *
     * @param o       The object to check.
     * @param type    The name of the object to use in the exception message.
     * @param message the message top send.
     * @throws RuntimeException if {@code o} is null.
     */
    private static void checkNull(final Object o, final String type, final String message) {
        if (o == null) {
            throw new RuntimeException(type + ">" + message);
        }
    }

    /**
     * Creates a GameProfile with a random UUID based on the base64 string and sets
     * the textures property with the given base64 texture.
     *
     * @param b64 The base64 encoded texture string.
     * @return A GameProfile with the texture property.
     */
    private static GameProfile makeProfile(String b64) {
        // random uuid based on the b64 string
        UUID id = new UUID(
                b64.substring(b64.length() - 20).hashCode(),
                b64.substring(b64.length() - 10).hashCode()
        );
        GameProfile profile = new GameProfile(id, "aaaa");
        profile.getProperties().put(TEXTURES, new Property(TEXTURES, b64));
        return profile;
    }

    /**
     * Mutates the block's Skull state to use the given base64 encoded skin texture.
     *
     * @param block The Skull block state to mutate.
     * @param b64   The base64 texture string.
     */
    private static void mutateBlockState(Skull block, String b64) {
        try {
            if (blockProfileField == null) {
                blockProfileField = block.getClass().getDeclaredField(PROFILE);
                blockProfileField.setAccessible(true);
            }
            blockProfileField.set(block, makeProfile(b64));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOG.log(e, () -> "Failed to change the skull block");
        }
    }

    /**
     * Mutates the SkullMeta of an item to use the given base64 encoded skin texture.
     *
     * @param meta The SkullMeta to mutate.
     * @param b64  The base64 texture string.
     */
    private static void mutateItemMeta(SkullMeta meta, String b64) {
        if (checks.isDoesHaveOwnerProfile()) {
            try {
                meta.setOwnerProfile(makePlayerProfile(b64));
            } catch (MalformedURLException ex2) {
                LOG.log(ex2, () -> "Can't invoke the profile from the SkullMeta.");
            }
            return;
        }
        try {
            if (metaSetProfileMethod == null) {
                metaSetProfileMethod = meta.getClass().getDeclaredMethod("setProfile", GameProfile.class);
                metaSetProfileMethod.setAccessible(true);
            }
            metaSetProfileMethod.invoke(meta, makeProfile(b64));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            // if in an older API where there is no setProfile method,
            // we set the profile field directly.
            try {
                if (metaProfileField == null) {
                    metaProfileField = meta.getClass().getDeclaredField(PROFILE);
                    metaProfileField.setAccessible(true);
                }
                metaProfileField.set(meta, makeProfile(b64));

            } catch (NoSuchFieldException | IllegalAccessException ex2) {
                LOG.log(ex2, () -> "Fail to get the profile");
            }
        }
    }

    private static String getUrl(final GameProfile profile) {
        for (Property property : profile.getProperties().get(TEXTURES)) {
            String value = property.getValue();
            try {
                // Decode Base64 -> JSON
                String json = new String(Base64.getDecoder().decode(value));
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

                return obj
                        .getAsJsonObject(TEXTURES)
                        .getAsJsonObject("SKIN")
                        .get("url")
                        .getAsString();
            } catch (IllegalArgumentException e) {
                LOG.log(e, () -> "Failed to decode skull texture property for profile: " + profile.getId());
            }
        }
        return null;
    }

    private static void setSkin(@Nonnull final GameProfile profile, @Nullable final String url) {
        if (url == null) return;

        JsonObject skinJson = new JsonObject();
        JsonObject textures = new JsonObject();
        JsonObject skin = new JsonObject();
        skin.addProperty("url", url);
        textures.add("SKIN", skin);
        skinJson.add(TEXTURES, textures);
        String encoded = Base64.getEncoder().encodeToString(skinJson.toString().getBytes(StandardCharsets.UTF_8));
        profile.getProperties().put(TEXTURES, new Property(TEXTURES, encoded));

    }


    /**
     * Retrieves a Material by name, logging a warning if not found.
     *
     * @param name The name of the Material.
     * @return The Material if found; null otherwise.
     */
    @Nullable
    private static Material getMaterial(final String name) {
        try {
            return Material.getMaterial(name);
        } catch (Exception e) {
            LOG.log(() -> "Could not find this material: " + name);
        }
        return null;
    }

    /**
     * Sets the owning player of the SkullMeta using the appropriate method depending on Bukkit API version.
     *
     * @param meta   The SkullMeta to modify.
     * @param player The OfflinePlayer to set as owner.
     */
    private static void setOwningPlayer(SkullMeta meta, OfflinePlayer player) {
        try {
            if (checks.isLegacy()) {
                meta.setOwner(player.getName());
            } else {
                meta.setOwningPlayer(player);
            }
        } catch (Exception ignored) {
            //We ignore the thrown error.
        }
    }

    /**
     * Converts a Mojang skin URL to a base64 encoded string for skull textures.
     *
     * @param url The Mojang skin URL.
     * @return The base64 encoded texture string.
     * @throws Validate.ValidateExceptions if the URL syntax is invalid.
     */
    private static String urlToBase64(String url) {
        URI actualUrl;
        try {
            actualUrl = new URI(url);
        } catch (URISyntaxException e) {
            throw new Validate.ValidateExceptions(e, "Could not create the Base64 from the url");
        }
        String toEncode = "{\"textures\":{\"SKIN\":{\"url\":\"" + actualUrl + "\"}}}";
        return Base64.getEncoder().encodeToString(toEncode.getBytes());
    }

    /**
     * Creates a PlayerProfile with textures set from the given base64 string.
     *
     * @param b64 The base64 encoded texture string.
     * @return The PlayerProfile with the skin set.
     * @throws MalformedURLException if the texture URL is malformed.
     */
    private static PlayerProfile makePlayerProfile(final String b64) throws MalformedURLException {
        UUID id = new UUID(
                b64.substring(b64.length() - 20).hashCode(),
                b64.substring(b64.length() - 10).hashCode()
        );

        PlayerProfile profile = Bukkit.createPlayerProfile(id);
        PlayerTextures textures = profile.getTextures();
        URL urlFromBase64 = getUrlFromBase64(b64);
        if (urlFromBase64 == null)
            return profile;
        textures.setSkin(urlFromBase64);
        profile.setTextures(textures);
        return profile;
    }

    /**
     * Parses a URL from the given base64 encoded texture string.
     *
     * @param base64 The base64 encoded texture string.
     * @return The URL of the skin texture, or null if parsing failed.
     */
    private static URL getUrlFromBase64(final String base64) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(decoded).getAsJsonObject();

            if (!obj.has(TEXTURES)) return null;
            JsonObject textures = obj.getAsJsonObject(TEXTURES);
            if (!textures.has("SKIN")) return null;
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (!skin.has("url")) return null;

            return new URL(skin.get("url").getAsString());
        } catch (IllegalArgumentException e) {
            LOG.log(() -> "Invalid Base64 input, cannot decode texture: " + base64);
        } catch (MalformedURLException e) {
            LOG.log(e, () -> "Failed to parse base64 texture to URL. With Base64 input: " + base64);
        }
        return null;
      /*
        try {
            String decoded = new String(Base64.getDecoder().decode(base64));
            return new URL(decoded.substring("{\"textures\":{\"SKIN\":{\"url\":\"".length(), decoded.length() - "\"}}}".length()));
        } catch (IllegalArgumentException exception) {
            LOG.log(() -> "Failed to parse the Base64 string, does you provide a valid base64 string? The input string: " + base64);
        }
        return null;*/
    }

    public static class CheckClassesExist {
        private boolean legacy;
        private boolean warningPosted = false;
        private boolean doesHaveOwnerProfile = true;
        private boolean doesHavePlayerProfile = true;

        /**
         * When instance is created it will check what classes exists for your minecraft version.
         */
        public CheckClassesExist() {
            checkLegacy();
            try {
                Class<?> skullMeta = Class.forName("org.bukkit.inventory.meta.SkullMeta");
                skullMeta.getMethod("setOwningPlayer", OfflinePlayer.class);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                legacy = true;
            }
            try {
                final UUID id = UUID.randomUUID();
                PlayerProfile profile = Bukkit.createPlayerProfile(id);
            } catch (NoClassDefFoundError | NoSuchMethodError e) {
                doesHavePlayerProfile = false;
            }
            final ItemStack skull = createSkull();
            if (skull != null) {
                final ItemMeta skullMeta = Bukkit.getItemFactory().getItemMeta(skull.getType());
                if (skullMeta != null) {
                    checkIfHasOwnerMethod((SkullMeta) skullMeta);
                }
            }
        }

        /**
         * Check if the Minecraft version is below 1.13.
         *
         * @return true if its legacy.
         */
        public boolean isLegacy() {
            return legacy;
        }

        /**
         * Check if you running legacy on modern minecraft version.
         *
         * @return Returns true if you did not set up correct API version.
         */
        public boolean isWarningPosted() {
            return warningPosted;
        }

        /**
         * Checks if it has OwnerProfile setter in the metadata class.
         *
         * @return Returns true if the profile exists.
         */
        public boolean isDoesHaveOwnerProfile() {
            return doesHaveOwnerProfile;
        }

        /**
         * If it modern version where PlayerProfile class exists.
         *
         * @return Returns true if PlayerProfile class exists.
         */
        public boolean isDoesHavePlayerProfile() {
            return doesHavePlayerProfile;
        }

        /**
         * Checks if running on legacy Bukkit API and logs a warning if using legacy API on modern server.
         */
        private void checkLegacy() {
            try {
                // if both of these succeed, then we are running
                // in a legacy api, but on a modern (1.13+) server.
                Material.class.getDeclaredField(PLAYER_HEAD);
                Material.valueOf("SKULL");

                if (!warningPosted) {
                    LOG.log(() -> "SKULLCREATOR API - Using the legacy bukkit API with 1.13+ bukkit versions is not supported!");
                    warningPosted = true;
                }
            } catch (NoSuchFieldException | IllegalArgumentException ignored) {
                //We don't need to know a error is thrown. This only checks so you don't use wrong API version.
            }
        }

        /**
         * Checks whether the SkullMeta class has the setOwnerProfile method
         * and updates the flag accordingly.
         *
         * @param meta The SkullMeta instance to check.
         */
        private void checkIfHasOwnerMethod(final SkullMeta meta) {
            try {
                meta.getClass().getDeclaredMethod("setOwnerProfile", PlayerProfile.class);
            } catch (NoSuchMethodException | NoClassDefFoundError exception) {
                doesHaveOwnerProfile = false;
            }
        }


    }

}