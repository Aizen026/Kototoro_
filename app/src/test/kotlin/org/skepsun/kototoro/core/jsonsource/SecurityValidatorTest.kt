package org.skepsun.kototoro.core.jsonsource

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SecurityValidator.
 * 
 * Tests URL validation, regex validation, input sanitization, and file size validation.
 */
class SecurityValidatorTest {
	
	// ========== URL Validation Tests ==========
	
	@Test
	fun `validateUrl accepts valid HTTP URL`() {
		val result = SecurityValidator.validateUrl("http://example.com")
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun `validateUrl accepts valid HTTPS URL`() {
		val result = SecurityValidator.validateUrl("https://example.com/path")
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun `validateUrl rejects empty URL`() {
		val result = SecurityValidator.validateUrl("")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("empty") })
	}
	
	@Test
	fun `validateUrl rejects blank URL`() {
		val result = SecurityValidator.validateUrl("   ")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("empty") })
	}
	
	@Test
	fun `validateUrl rejects FTP protocol`() {
		val result = SecurityValidator.validateUrl("ftp://example.com")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("protocol") })
	}
	
	@Test
	fun `validateUrl rejects file protocol`() {
		val result = SecurityValidator.validateUrl("file:///etc/passwd")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("protocol") })
	}
	
	@Test
	fun `validateUrl rejects localhost`() {
		val result = SecurityValidator.validateUrl("http://localhost:8080")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("local") })
	}
	
	@Test
	fun `validateUrl rejects 127_0_0_1`() {
		val result = SecurityValidator.validateUrl("http://127.0.0.1")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("local") })
	}
	
	@Test
	fun `validateUrl rejects 0_0_0_0`() {
		val result = SecurityValidator.validateUrl("http://0.0.0.0")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("local") })
	}
	
	@Test
	fun `validateUrl rejects 192_168 private network`() {
		val result = SecurityValidator.validateUrl("http://192.168.1.1")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("local") })
	}
	
	@Test
	fun `validateUrl rejects 10_x private network`() {
		val result = SecurityValidator.validateUrl("http://10.0.0.1")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("local") })
	}
	
	@Test
	fun `validateUrl rejects 172_16-31 private network`() {
		val result1 = SecurityValidator.validateUrl("http://172.16.0.1")
		assertFalse(result1.isValid)
		assertTrue(result1.errors.any { it.contains("local") })
		
		val result2 = SecurityValidator.validateUrl("http://172.31.255.255")
		assertFalse(result2.isValid)
		assertTrue(result2.errors.any { it.contains("local") })
	}
	
	@Test
	fun `validateUrl accepts 172_15 (not in private range)`() {
		val result = SecurityValidator.validateUrl("http://172.15.0.1")
		assertTrue(result.isValid)
	}
	
	@Test
	fun `validateUrl accepts 172_32 (not in private range)`() {
		val result = SecurityValidator.validateUrl("http://172.32.0.1")
		assertTrue(result.isValid)
	}
	
	@Test
	fun `validateUrl rejects URL without host`() {
		val result = SecurityValidator.validateUrl("http://")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("hostname") })
	}
	
	@Test
	fun `validateUrl rejects malformed URL`() {
		val result = SecurityValidator.validateUrl("not a url")
		assertFalse(result.isValid)
		assertTrue(result.errors.isNotEmpty())
	}
	
	@Test
	fun `validateUrl rejects url triggering URISyntaxException`() {
		val result = SecurityValidator.validateUrl("http://example.com/invalid space")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("Invalid URL format") })
	}

	// ========== Regex Validation Tests ==========
	
	@Test
	fun `validateRegex accepts simple pattern`() {
		val result = SecurityValidator.validateRegex("hello")
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun `validateRegex accepts pattern with capture group`() {
		val result = SecurityValidator.validateRegex("<title>(.*?)</title>")
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun `validateRegex accepts pattern with character class`() {
		val result = SecurityValidator.validateRegex("[a-zA-Z0-9]+")
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun `validateRegex rejects pattern longer than 500 characters`() {
		val longPattern = "a".repeat(501)
		val result = SecurityValidator.validateRegex(longPattern)
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("too long") })
	}
	
	@Test
	fun `validateRegex accepts pattern with exactly 500 characters`() {
		val pattern = "a".repeat(500)
		val result = SecurityValidator.validateRegex(pattern)
		assertTrue(result.isValid)
	}
	
	@Test
	fun `validateRegex rejects nested star quantifiers`() {
		val result = SecurityValidator.validateRegex("(.*)*")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("dangerous") || it.contains("quantifier") })
	}
	
	@Test
	fun `validateRegex rejects nested plus quantifiers`() {
		val result = SecurityValidator.validateRegex("(a+)+")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("dangerous") || it.contains("quantifier") })
	}
	
	@Test
	fun `validateRegex rejects redundant alternation with quantifier`() {
		val result = SecurityValidator.validateRegex("(a|a)*")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("dangerous") || it.contains("alternation") })
	}
	
	@Test
	fun `validateRegex rejects invalid regex syntax`() {
		val result = SecurityValidator.validateRegex("(unclosed")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("syntax") })
	}
	
	@Test
	fun `validateRegex rejects multiple dangerous patterns`() {
		val result = SecurityValidator.validateRegex("(a*)*")
		assertFalse(result.isValid)
		assertTrue(result.errors.isNotEmpty())
	}
	
	// ========== HTML Sanitization Tests ==========
	
	@Test
	fun `sanitizeHtmlInput escapes ampersand`() {
		val result = SecurityValidator.sanitizeHtmlInput("A & B")
		assertEquals("A &amp; B", result)
	}
	
	@Test
	fun `sanitizeHtmlInput escapes less than`() {
		val result = SecurityValidator.sanitizeHtmlInput("<script>")
		assertEquals("&lt;script&gt;", result)
	}
	
	@Test
	fun `sanitizeHtmlInput escapes greater than`() {
		val result = SecurityValidator.sanitizeHtmlInput("a > b")
		assertEquals("a &gt; b", result)
	}
	
	@Test
	fun `sanitizeHtmlInput escapes double quote`() {
		val result = SecurityValidator.sanitizeHtmlInput("say \"hello\"")
		assertEquals("say &quot;hello&quot;", result)
	}
	
	@Test
	fun `sanitizeHtmlInput escapes single quote`() {
		val result = SecurityValidator.sanitizeHtmlInput("it's")
		assertEquals("it&#x27;s", result)
	}
	
	@Test
	fun `sanitizeHtmlInput escapes forward slash`() {
		val result = SecurityValidator.sanitizeHtmlInput("a/b")
		assertEquals("a&#x2F;b", result)
	}
	
	@Test
	fun `sanitizeHtmlInput escapes XSS attempt`() {
		val xss = "<script>alert('XSS')</script>"
		val result = SecurityValidator.sanitizeHtmlInput(xss)
		assertEquals("&lt;script&gt;alert(&#x27;XSS&#x27;)&lt;&#x2F;script&gt;", result)
	}
	
	@Test
	fun `sanitizeHtmlInput handles empty string`() {
		val result = SecurityValidator.sanitizeHtmlInput("")
		assertEquals("", result)
	}
	
	@Test
	fun `sanitizeHtmlInput handles normal text`() {
		val result = SecurityValidator.sanitizeHtmlInput("Hello World")
		assertEquals("Hello World", result)
	}
	
	// ========== File Size Validation Tests ==========
	
	@Test
	fun `validateJsonFileSize accepts 1MB file`() {
		val oneMB = 1024L * 1024L
		val result = SecurityValidator.validateJsonFileSize(oneMB)
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun `validateJsonFileSize accepts exactly 5MB file`() {
		val fiveMB = 5L * 1024L * 1024L
		val result = SecurityValidator.validateJsonFileSize(fiveMB)
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun `validateJsonFileSize rejects 6MB file`() {
		val sixMB = 6L * 1024L * 1024L
		val result = SecurityValidator.validateJsonFileSize(sixMB)
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("too large") })
	}
	
	@Test
	fun `validateJsonFileSize rejects 10MB file`() {
		val tenMB = 10L * 1024L * 1024L
		val result = SecurityValidator.validateJsonFileSize(tenMB)
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("5MB") })
	}
	
	@Test
	fun `validateJsonFileSize accepts empty file`() {
		val result = SecurityValidator.validateJsonFileSize(0L)
		assertTrue(result.isValid)
	}
	
	// ========== Field Format Validation Tests ==========
	
	@Test
	fun `validateFieldFormat accepts valid field`() {
		val result = SecurityValidator.validateFieldFormat("name", "Valid Name")
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun `validateFieldFormat rejects empty field`() {
		val result = SecurityValidator.validateFieldFormat("name", "")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("name") && it.contains("empty") })
	}
	
	@Test
	fun `validateFieldFormat rejects blank field`() {
		val result = SecurityValidator.validateFieldFormat("name", "   ")
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("empty") })
	}
	
	@Test
	fun `validateFieldFormat rejects field exceeding max length`() {
		val longValue = "a".repeat(1001)
		val result = SecurityValidator.validateFieldFormat("name", longValue, maxLength = 1000)
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("too long") })
	}
	
	@Test
	fun `validateFieldFormat accepts field at max length`() {
		val value = "a".repeat(1000)
		val result = SecurityValidator.validateFieldFormat("name", value, maxLength = 1000)
		assertTrue(result.isValid)
	}
	
	@Test
	fun `validateFieldFormat uses custom max length`() {
		val value = "a".repeat(51)
		val result = SecurityValidator.validateFieldFormat("name", value, maxLength = 50)
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("50") })
	}
}
