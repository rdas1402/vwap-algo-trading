package com.trading;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SuiteDisplayName("Trading Application Test Suite")
@SelectPackages("com.trading")
public class TestSuiteRunner {
    // This class serves as the test suite runner
    // All tests in the com.trading package will be executed
}