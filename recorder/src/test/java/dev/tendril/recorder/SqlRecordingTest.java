package dev.tendril.recorder;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class SqlRecordingTest {
    @Test
    void countsExecutionsAndOmitsBoundValues() throws SQLException {
        PreparedStatement delegate = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "executeUpdate" -> throw new SQLException("failed");
                    default -> null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> delegate);
        assertSame(connection, SqlRecording.wrap(connection));

        SqlRecording recording = SqlRecording.begin();
        try {
            Connection wrapped = SqlRecording.wrap(connection);
            PreparedStatement query = wrapped.prepareStatement("select * from account where token = ? and id = 42");
            query.setString(1, "bound secret");
            assertTrue(query.execute());
            assertThrows(SQLException.class, query::executeUpdate);
            wrapped.prepareStatement("select 'inline secret'").execute();
        } finally {
            SqlRecording.end();
        }

        Json event = Json.object();
        recording.writeTo(event);
        String payload = event.build();
        assertTrue(payload.contains("\"template\":\"select * from account where token = ? and id = ?\",\"count\":2"));
        assertTrue(payload.contains("\"template\":\"SQL text omitted\",\"count\":1"));
        assertFalse(payload.contains("secret"));
    }
}
