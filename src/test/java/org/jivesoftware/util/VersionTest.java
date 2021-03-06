package org.jivesoftware.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.jivesoftware.util.Version.ReleaseStatus;
import org.junit.Test;

public class VersionTest {

	@Test
	public void testVersionWithInitializingConstructor() {
		Version test = new Version(3, 2, 1, ReleaseStatus.Beta, 4);
		
		assertEquals(3, test.getMajor());
		assertEquals(2, test.getMinor());
		assertEquals(1, test.getMicro());
		
		assertEquals(ReleaseStatus.Beta, test.getStatus());
		assertEquals(4, test.getStatusVersion());
		
		assertEquals("3.2.1 Beta 4", test.getVersionString());
	}

	@Test
	public void testVersionWithRegularStringConstructor() {
		Version test = new Version("1.2.3");
		
		assertEquals(1, test.getMajor());
		assertEquals(2, test.getMinor());
		assertEquals(3, test.getMicro());
		
		assertEquals(ReleaseStatus.Release, test.getStatus());
		assertEquals(-1, test.getStatusVersion());
		
		assertEquals("1.2.3", test.getVersionString());
	}

	@Test
	public void testVersionWithNullStringConstructor() {
		Version test = new Version(null);
		
		assertEquals(0, test.getMajor());
		assertEquals(0, test.getMinor());
		assertEquals(0, test.getMicro());
		
		assertEquals(ReleaseStatus.Release, test.getStatus());
		assertEquals(-1, test.getStatusVersion());
		
		assertEquals("0.0.0", test.getVersionString());
	}

	@Test
	public void testVersionComparisons() {
		
		Version test123 = new Version("1.2.3");
		Version test321 = new Version("3.2.1");
		Version test322 = new Version("3.2.2");
		Version test333 = new Version("3.3.3");
		Version test300 = new Version("3.0.0");
		Version test3100 = new Version("3.10.0");
		Version test29999 = new Version("2.99.99");
		
		assertEquals(-1, test123.compareTo(test321));
		assertEquals(0, test123.compareTo(test123));
		assertEquals(1, test321.compareTo(test123));
		
		assertTrue(test322.isNewerThan(test321));
		assertFalse(test322.isNewerThan(test333));
		assertFalse(test300.isNewerThan(test321));
		assertTrue(test3100.isNewerThan(test333));
		assertTrue(test3100.isNewerThan(test29999));
		assertTrue(test300.isNewerThan(test29999));
		
	}

}
