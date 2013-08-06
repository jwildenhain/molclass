package nick.test;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;

import javax.mail.*;
import javax.mail.internet.*;
import java.util.*; 

public class emailtest {
  
  public static void main(String[] args) throws MessagingException
  {
    String[] to = {"nfitzgerald.ubc@googlemail.com"};
    //sendEmail("nfitzgerald.ubc@googlemail.com", "chemgrid", "test", "Yarrr, this be a test, Matey!");
    postMail(to, "test", "Yarrr, this be a test, Matey!", "chemgrid");
  }
  
  
  

  private static void sendEmail(String to, String from, String subject, String message) throws IOException
  {
    File sendmail = new File("/usr/lib/sendmail -oi -t|");
    FileOutputStream os = new FileOutputStream(sendmail);
    BufferedOutputStream bos = new BufferedOutputStream(os);
    DataOutputStream out = new DataOutputStream(bos);

    
    out.writeChars("From: " + from);
    out.writeChars("To: " + to);
    out.writeChars("Subject: " + subject);
    out.writeChars(message);
    
    out.close();
    
//  my ($to, $from, $subject, $message) = @_;
//  my $sendmail = '/usr/lib/sendmail';
//  open(MAIL, "|$sendmail -oi -t");
//  print MAIL "From: $from\n";
//  print MAIL "To: $to\n";
//  print MAIL "Subject: $subject\n\n";
//  print MAIL "$message\n";
//  close(MAIL);
  } 
  
  private static void postMail( String recipients[ ], String subject, String message , String from) throws MessagingException
  {
      boolean debug = false;

       //Set the host smtp address
       Properties props = new Properties();
       props.put("mail.smtp.host", "localhost");

      // create some properties and get the default Session
      Session session = Session.getDefaultInstance(props, null);
      session.setDebug(debug);

      // create a message
      Message msg = new MimeMessage(session);

      // set the from and to address
      InternetAddress addressFrom = new InternetAddress(from);
      msg.setFrom(addressFrom);

      InternetAddress[] addressTo = new InternetAddress[recipients.length]; 
      for (int i = 0; i < recipients.length; i++)
      {
          addressTo[i] = new InternetAddress(recipients[i]);
      }
      msg.setRecipients(Message.RecipientType.TO, addressTo);
     

      // Optional : You can also set your custom headers in the Email if you Want
      msg.addHeader("MyHeaderName", "myHeaderValue");

      // Setting the Subject and Content Type
      msg.setSubject(subject);
      msg.setContent(message, "text/plain");
      Transport.send(msg);
  }
  
}
