package org.econtent;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import javax.xml.parsers.ParserConfigurationException;

import org.API.Freegal.FreegalAPI;
import org.apache.log4j.Logger;
import org.ini4j.Ini;
import org.vufind.IRecordProcessor;
import org.vufind.ProcessorResults;
import org.xml.sax.SAXException;

public class FreegalImporter implements IRecordProcessor {
	private Logger logger;
	private ProcessorResults results;
	private String freegalUrl;
	private String freegalUser;
	private String freegalPIN;
	private String freegalAPIkey;
	private String libraryId;
	private FreegalAPI freegalAPI;
	private EContentRecordDAO dao;

	public void importRecords() {
		logger.info("Importing Freegal API Items");
		try {
			EContentRecordDAO dao = EContentRecordDAO.getInstance();
			Collection<String> genres = freegalAPI.getAllGenres();
			logger.info("Freegal Gender Number: " + genres.size());
            int songsAdded = 0;

            // Remove all existing songs for the album from the
            // database since freegal doesn't keep unique ids
            dao.deleteFreegalItems();
			for (String genre : genres) {
                ArrayList<Album> albums = new ArrayList(freegalAPI.getAlbums(genre));

                int partitionSize = 100;
                for(int i = 0; albums.size() > i*partitionSize; i++) {
                    Collection<Album> partition = albums.subList(i*partitionSize,
                            (i+1)*partitionSize < albums.size() -1 ? (i+1)*partitionSize : albums.size() -1);
                    processAlbumPartition(partition);
                }
			}
		} catch (ParserConfigurationException e) {
			logger.error("Error downloading Freegal contents.", e);
			results.addNote("Error downloading Freegal contents. "
					+ e.getMessage());
		} catch (SAXException e) {
			logger.error("Error downloading Freegal contents.", e);
			results.addNote("Error downloading Freegal contents. "
					+ e.getMessage());
		} catch (IOException e) {
			logger.error("Error downloading Freegal contents.", e);
			results.addNote("Error downloading Freegal contents. "
					+ e.getMessage());
		} catch (SQLException e) {
			logger.error("Error adding/updating Freegal records.", e);
			results.addNote("Error adding/updating Freegal records. "
					+ e.getMessage());
			results.incErrors();
		}
	}

    private void syncEContentRecordsInDB(Collection<Album> albums) throws SQLException{
        for (Album album : albums) {
            // increment number of econtent record processed
            results.incEContentRecordsProcessed();

            logger.info("Processing album " + album.getTitle()
                    + " the album has " + album.getSongs().size()
                    + " songs");
            EContentRecord record = dao.findByTitleAndAuthor(
                    album.getTitle(), album.getAuthor());
            boolean added = false;
            if (record == null) {
                // create new record
                record = new EContentRecord();
                record.set("date_added",
                        (int) (new Date().getTime() / 100));
                record.set("addedBy", -1);
                record.set("accessType", "free");
                record.set("source", "Freegal");
                record.set("availableCopies", 1);
                added = true;
            } else {

                // set date updated
                record.set("date_updated",
                        (int) (new Date().getTime() / 100));
            }
            record.set("status", "active");
            record.set("title", album.getTitle());
            record.set("author", album.getAuthor());
            record.set("author2", album.getAuthor2());
            record.set("contents", album.getContents());
            record.set("language", album.getLanguage());
            record.set("genre", album.getGenre());
            record.set("collection", album.getCollection());
            record.set("cover", album.getCoverUrl());
            dao.save(record);

            if (added) {
                results.incAdded();
            } else {
                results.incUpdated();
            }
        }
    }
    private void processAlbumPartition(Collection<Album> albums) throws SQLException {
        int songsAdded = 0;
        for (Album album : albums) {
            syncEContentRecordsInDB(albums);
            EContentRecord record = dao.findByTitleAndAuthor(
                    album.getTitle(), album.getAuthor());
            String recordId = record.get("id").toString();

            // Add songs to the database
            for (Song song : album.getSongs()) {
                String notes = song.getTitle();
                String songArtist = song.getArtist();
                if (songArtist != null && !songArtist.equals(album.getAuthor())) {
                    notes += " -- " + song.getArtist();
                }

                EContentItem item = new EContentItem(
                        null,
                        recordId,
                        song.getDownloadUrl(),
                        "externalMP3",
                        notes,
                        null,
                        (int) (new Date().getTime() / 100),
                        (int) (new Date().getTime() / 100));
                dao.addEContentItem(item);
            }
        }
        dao.flushEContentItems(false);
    }

	@Override
	public boolean init(Ini configIni, String serverName, long reindexLogId,
			Connection vufindConn, Connection econtentConn, Logger logger) {
		this.logger = logger;
		results = new ProcessorResults("Import Freegal Content", reindexLogId,
				vufindConn, logger);
		if (!loadConfig(configIni)) {
			logger.error("Error loading Freegal API settings from config.ini.");
			results.addNote("Error loading Freegal API settings from config.ini.");
			return false;
		}

		// Get an instance of EContentRecordDAO class
		dao = EContentRecordDAO.getInstance();

		// Initialize freegal api
		freegalAPI = new FreegalAPI(freegalUrl, freegalUser, freegalPIN,
				freegalAPIkey, libraryId);

		// flag all existing Freegal records as "deleted"
		try {
			dao.flagAllFreegalRecordsAsDeleted();
		} catch (SQLException e) {
			logger.error("Error flagging all Freegal records as \"deleted\".",
					e);
			results.addNote("Error flagging all Freegal records as \"deleted\". "
					+ e.getMessage());
			return false;
		}

		return true;
	}

	@Override
	public void finish() {
		try {
			int deleted = dao.deleteFreegalRecordsFlaggedAsDeleted();
			while (deleted-- > 0) {
				results.incDeleted();
			}
		} catch (SQLException e) {
			logger.error("Error deleting records flagged as deleted", e);
			results.addNote("Error deleting records flagged as deleted."
					+ e.getMessage());
			results.incErrors();
		}
		results.saveResults();
	}

	@Override
	public ProcessorResults getResults() {
		return results;
	}

	private boolean loadConfig(Ini configIni) {
		freegalUrl = configIni.get("FreeGal", "freegalUrl");
		if (freegalUrl == null || freegalUrl.length() == 0) {
			logger.error("Freegal API URL not found.  Please specify url in freegalUrl key.");
			return false;
		}

		freegalUser = configIni.get("FreeGal", "freegalUser");
		if (freegalUser == null || freegalUser.length() == 0) {
			logger.error("Freegal User not found.  Please specify the barcode of a patron to use while loading freegal information in the freegalUser key.");
			return false;
		}

		freegalPIN = configIni.get("FreeGal", "freegalPIN");
		if (freegalPIN == null || freegalPIN.length() == 0) {
			logger.error("Freegal PIN not found in.  Please specify the PIN of a patron to use while loading freegal information in the freegalPIN key.");
			return false;
		}
		freegalAPIkey = configIni.get("FreeGal", "freegalAPIkey");
		if (freegalAPIkey == null || freegalAPIkey.length() == 0) {
			logger.error("Freegal API Key not found.  Please specify the API Key for the Freegal webservices in the freegalAPIkey key.");
			return false;
		}
		libraryId = configIni.get("FreeGal", "libraryId");
		if (libraryId == null || libraryId.length() == 0) {
			logger.error("Freegal Library Id not found.  Please specify the Library for the Freegal webservices the libraryId key.");
			return false;
		}
		return true;
	}
}
