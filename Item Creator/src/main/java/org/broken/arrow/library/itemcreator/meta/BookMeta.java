package org.broken.arrow.library.itemcreator.meta;

import org.broken.arrow.library.itemcreator.ItemCreator;
import org.bukkit.inventory.meta.ItemMeta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents the metadata for a book item, including its title, author,
 * generation, and pages.
 * <p>
 * This class provides methods to get and set the book’s metadata, manipulate
 * its pages (which are 1-indexed), and apply the metadata to Bukkit's
 * {@link org.bukkit.inventory.meta.BookMeta} instances.
 * </p>
 * <p>
 * Pages have a maximum length of 256 characters, and the book supports up
 * to a very high maximum page count (limited by {@link Integer#MAX_VALUE}).
 * </p>
 * <p>
 * Titles are limited to 32 characters (as per Minecraft limitations),
 * and generation refers to how the book was created (original, copy, etc.).
 * </p>
 */
public class BookMeta {
    private static final boolean modernVersion = ItemCreator.getServerVersion() > 12.2F;
    private List<String> pages = new ArrayList<>();
    private String generation;
    private String title;
    private String author;
    static final int MAX_PAGES = Integer.MAX_VALUE;
    static final int MAX_PAGE_LENGTH = 256;

    /**
     * Creates a new {@code BookMeta} instance by copying data from an existing
     * Bukkit {@link org.bukkit.inventory.meta.BookMeta} instance.
     *
     * @param bukkitBookMeta the source Bukkit book meta to copy from; must not be null.
     * @return a new {@code BookMeta} instance containing copied data.
     */
    @Nonnull
    public static BookMeta setBookMeta(@Nonnull final org.bukkit.inventory.meta.BookMeta bukkitBookMeta) {
        BookMeta bookMeta = new BookMeta();
        bookMeta.setAuthor(bukkitBookMeta.getAuthor());
        bookMeta.setTitle(bukkitBookMeta.getTitle());

        if (modernVersion && bukkitBookMeta.getGeneration() != null)
            bookMeta.setGeneration(bukkitBookMeta.getGeneration());

        bookMeta.setPages(bukkitBookMeta.getPages());

        return bookMeta;
    }

    /**
     * Gets the title of the book.
     *
     * @return the title of the book
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the title of the book.
     * <p>
     * The title is limited to 32 characters. Passing {@code null} will remove
     * the title.
     * </p>
     *
     * @param title the title to set, or {@code null} to remove it.
     */
    public void setTitle(@Nullable final String title) {
        this.title = title;
    }

    /**
     * Gets the author of the book.
     *
     * @return the author of the book
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Sets the author of the book. Removes author when given null.
     *
     * @param author the author to set
     */
    public void setAuthor(@Nullable final String author) {
        this.author = author;
    }

    /**
     * Gets the generation of the book.
     *
     * @return the generation of the book
     */
    public org.bukkit.inventory.meta.BookMeta.Generation getGeneration() {
        return org.bukkit.inventory.meta.BookMeta.Generation.valueOf(generation);
    }

    /**
     * Sets the generation of the book. Removes generation when given null.
     *
     * @param generation the generation to set
     */
    public void setGeneration(@Nullable final org.bukkit.inventory.meta.BookMeta.Generation generation) {
        this.generation =generation == null ? "COPY_OF_COPY": generation.name();
    }

    /**
     * Sets the specified page in the book. Pages of the book must be
     * contiguous.
     * <p>
     * The text can be up to 256 characters in length, additional characters
     * are truncated.
     * <p>
     * Pages are 1-indexed.
     *
     * @param page the page number to set, in range [1, getPageCount()]
     * @param text the text to set for that page
     */
    public void setPage(int page, @Nullable String text) {
        String newText = validatePage(text);
        pages.set(page - 1, newText);
    }

    /**
     * Clears the existing book pages, and sets the book to use the provided
     * pages. Maximum 50 pages with 256 characters per page.
     *
     * @param pages A list of strings, each being a page
     */
    public void setPages(@Nonnull String... pages) {
        this.setPages(Arrays.asList(pages));
    }

    /**
     * Clears the existing book pages, and sets the book to use the provided
     * pages. Maximum 100 pages with 256 characters per page.
     *
     * @param pages A list of pages to set the book to use
     */
    public void setPages(List<String> pages) {
        if (pages.isEmpty()) {
            this.pages = null;
            return;
        }

        if (this.pages != null) {
            this.pages.clear();
        }
        for (String page : pages) {
            addPage(page);
        }
    }

    /**
     * Adds new pages to the end of the book. Up to a maximum of 50 pages with
     * 256 characters per page.
     *
     * @param pages A list of strings, each being a page
     */
    public void addPage(@Nonnull String... pages) {
        for (String page : pages) {
            page = validatePage(page);
            internalAddPage(page);
        }
    }

    /**
     * Gets the specified page in the book. The given page must exist.
     * <p>
     * Pages are 1-indexed.
     *
     * @param page the page number to get, in range [1, getPageCount()]
     * @return the page from the book or empty string if not found the page.
     */
    public String getPage(final int page) {
        if (!isValidPage(page))
            return "";
        return pages.get(page - 1);
    }

    /**
     * Gets all the pages in the book.
     *
     * @return list of all the pages in the book
     */
    @Nonnull
    public List<String> getPages() {
        if (pages == null) return new ArrayList<>();
        return Collections.unmodifiableList(pages);
    }

    /**
     * Gets the number of pages in the book.
     *
     * @return the number of pages in the book
     */
    public int getPageCount() {
        return (pages == null) ? 0 : pages.size();
    }

    /**
     * Applies the data stored in this {@code BookMeta} instance to the given
     * Bukkit {@link org.bukkit.inventory.meta.BookMeta} object.
     * <p>
     * This method copies the title, author, generation, and pages from this
     * instance onto the provided Bukkit book meta.
     * If the given {@code ItemMeta} is not an instance of Bukkit's
     * {@code BookMeta}, this method does nothing.
     * </p>
     *
     * @param bookMeta the Bukkit {@code ItemMeta} to apply this book metadata to
     */
    public void applyBookMenta(final ItemMeta bookMeta) {
        if (!(bookMeta instanceof org.bukkit.inventory.meta.BookMeta))
            return;
        final org.bukkit.inventory.meta.BookMeta meta = (org.bukkit.inventory.meta.BookMeta) bookMeta;
        meta.setTitle(this.getTitle());
        meta.setAuthor(this.getAuthor());
        if (modernVersion)
            meta.setGeneration(this.getGeneration());
        meta.setPages(this.getPages());
    }

    private boolean isValidPage(int page) {
        return page > 0 && page <= getPageCount();
    }

    private String validatePage(String page) {
        if (page == null) {
            page = "";
        } else if (page.length() > MAX_PAGE_LENGTH) {
            page = page.substring(0, MAX_PAGE_LENGTH);
        }
        return page;
    }

    private void internalAddPage(String page) {
        // asserted: page != null
        if (this.pages == null) {
            this.pages = new ArrayList<>();
        } else if (this.pages.size() == MAX_PAGES) {
            return;
        }
        this.pages.add(page);
    }

}
